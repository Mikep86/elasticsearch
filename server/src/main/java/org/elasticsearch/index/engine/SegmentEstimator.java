/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.engine;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.MergeTrigger;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.Lock;
import org.apache.lucene.util.InfoStream;
import org.apache.lucene.util.StringHelper;
import org.apache.lucene.util.Version;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Estimates the segment geometry a shard will have <em>at rest</em> once it holds a larger number of documents than it does today.
 *
 * <p>The estimate is anchored on an existing index rather than on a sample document set, which is what makes it tractable: bytes-per-document,
 * the flush size, the compression gain from merging, the delete ratio and the per-shard document skew are all read off the live index instead
 * of being modelled. The only genuine extrapolation is of the size model out to segment sizes larger than any currently present, which is
 * reported back through {@link Confidence}.
 *
 * <p>Rather than reimplementing the merge policy's arithmetic, this class drives the real {@link TieredMergePolicy#findMerges} against a
 * synthetic {@link SegmentInfos}. "At rest" is therefore not a heuristic: the policy itself declares it by returning no merges, so the
 * simulation terminates on the same condition a live shard does and it keeps working across Lucene upgrades.
 *
 * <p>Known limitations, in rough order of how much they matter:
 * <ul>
 *     <li>Extrapolating the size model beyond the largest observed segment is the dominant error term. {@link Confidence} reports how far
 *     out of the observed range the estimate reaches.</li>
 *     <li>Index sort locality is not modelled. A large segment drawn from a large corpus has lower local sort density, and so compresses
 *     slightly worse, than an equally sized segment drawn from a small one. This is the one mechanism by which bytes-per-document genuinely
 *     depends on total index size, and it biases the estimate towards predicting <em>too few</em> segments on sorted indices.</li>
 *     <li>The source index is assumed to be at rest and not force-merged. Both are detected and downgrade the confidence, because a
 *     force-merged index invalidates the size model and the inferred flush size alike.</li>
 *     <li>The flush size is read from segments written under the source index's ingest concurrency. Different write concurrency on the way
 *     to the target shifts it, which matters more than it looks like it should: see {@link #inferFlushSize}.</li>
 *     <li>Drift in the shape of the data as the index grows is not modelled at all.</li>
 * </ul>
 */
public class SegmentEstimator {

    /**
     * How far outside the observed data the estimate had to reach.
     */
    public enum ConfidenceLevel {
        HIGH,
        MEDIUM,
        LOW
    }

    /**
     * @param level       how much the estimate can be trusted
     * @param explanation why, in terms a caller can act on
     */
    public record Confidence(ConfidenceLevel level, String explanation) {}

    /**
     * One predicted segment. {@code docCount} counts live documents only, matching {@link Segment#getNumDocs()}.
     */
    public record EstimatedSegment(long sizeInBytes, long docCount, long delDocCount) {
        public long maxDoc() {
            return docCount + delDocCount;
        }
    }

    /**
     * The predicted segments grouped into merge tiers, where tier 0 holds everything up to {@code index.merge.policy.floor_segment} and
     * each subsequent tier is a merge factor larger than the last. Tiers are the level at which the policy actually reasons, so this is
     * usually a more useful summary than a raw size histogram.
     */
    public record TierSummary(
        int tier,
        long minSizeInBytes,
        long maxSizeInBytes,
        int segmentCount,
        long totalSizeInBytes,
        long totalDocCount
    ) {}

    /**
     * @param segments                predicted segments, descending by size
     * @param observedBytesPerDoc     bytes per document measured at flush-level segment sizes on the source index
     * @param mergeRecoveryFactor     measured ratio of bytes-per-document in the largest observed segments to that at flush level; below 1
     *                                means merging recovers space, which is the normal case
     * @param assumedFlushSizeInBytes the flush size the estimate was produced with, whether supplied or inferred
     */
    public record ShardEstimate(
        List<EstimatedSegment> segments,
        long totalSizeInBytes,
        long totalDocCount,
        List<TierSummary> tiers,
        double observedBytesPerDoc,
        double mergeRecoveryFactor,
        long assumedFlushSizeInBytes,
        Confidence confidence
    ) {
        public int segmentCount() {
            return segments.size();
        }
    }

    /**
     * Bounds the simulation so that a wildly optimistic target document count cannot burn unbounded CPU in a caller's thread.
     */
    private static final int MAX_SIMULATED_FLUSHES = 200_000;

    /**
     * Guards against a merge selection that fails to make progress. The policy always either shrinks the segment count or reclaims
     * deletes, so this is a backstop against a future policy change rather than an expected outcome.
     */
    private static final int MAX_MERGE_ROUNDS = 100_000;

    private SegmentEstimator() {}

    /**
     * Estimates each shard independently, distributing {@code targetDocCount} across shards in proportion to the document counts they hold
     * today so that any existing routing skew is preserved rather than assumed away.
     *
     * @param currentSegments the segments of each shard, as returned by {@link Engine#segments()}
     * @param targetDocCount  the total live document count to extrapolate to, across all the given shards
     * @param mergePolicy     the shard's configured policy, from {@code IndexSettings#getMergePolicy}
     */
    public static Map<ShardId, ShardEstimate> estimate(
        Map<ShardId, List<Segment>> currentSegments,
        long targetDocCount,
        MergePolicy mergePolicy
    ) {
        if (currentSegments.isEmpty()) {
            throw new IllegalArgumentException("cannot estimate segments without at least one shard's segments");
        }
        final long currentTotal = currentSegments.values().stream().mapToLong(SegmentEstimator::liveDocs).sum();
        if (currentTotal <= 0) {
            throw new IllegalArgumentException("cannot estimate segments from an index holding no live documents");
        }
        requireTargetNotSmaller(targetDocCount, currentTotal);

        final Map<ShardId, ShardEstimate> estimates = new LinkedHashMap<>();
        for (Map.Entry<ShardId, List<Segment>> shard : currentSegments.entrySet()) {
            final long shardCurrent = liveDocs(shard.getValue());
            // Preserve the observed skew: a shard holding a tenth of the documents today is assumed to hold a tenth of them at the target.
            final long shardTarget = Math.max(shardCurrent, Math.round(targetDocCount * ((double) shardCurrent / currentTotal)));
            estimates.put(shard.getKey(), estimateShard(shard.getValue(), shardTarget, mergePolicy));
        }
        return estimates;
    }

    /**
     * Estimates a single shard, inferring the flush size from the observed segments.
     */
    public static ShardEstimate estimateShard(List<Segment> currentSegments, long targetDocCount, MergePolicy mergePolicy) {
        return estimateShard(currentSegments, targetDocCount, mergePolicy, inferFlushSize(currentSegments, mergePolicy));
    }

    /**
     * Estimates a single shard with an explicit flush size.
     *
     * <p>Callers who care about the accuracy of the result should sweep {@code flushSizeInBytes} across the range they consider plausible
     * and report the band, because the flush size is not a second-order input: it sets the <em>effective</em> maximum segment size by
     * bin-packing against {@code max_merged_segment}. A merge closes early once the next input would cross the cap, so flushes that divide
     * into the cap unevenly leave a permanent shortfall in the top tier.
     *
     * @param currentSegments   the shard's current segments; must be non-empty and hold at least one live document
     * @param targetDocCount    live document count to extrapolate to; must not be below the current count
     * @param mergePolicy       must be a {@link TieredMergePolicy}
     * @param flushSizeInBytes  on-disk size of a single flushed segment
     */
    public static ShardEstimate estimateShard(
        List<Segment> currentSegments,
        long targetDocCount,
        MergePolicy mergePolicy,
        long flushSizeInBytes
    ) {
        final TieredMergePolicy policy = requireTieredMergePolicy(mergePolicy);
        final List<Segment> observed = withKnownSize(currentSegments);
        if (observed.isEmpty()) {
            throw new IllegalArgumentException("cannot estimate segments without at least one segment of known size");
        }
        final long currentDocCount = liveDocs(observed);
        if (currentDocCount <= 0) {
            throw new IllegalArgumentException("cannot estimate segments from a shard holding no live documents");
        }
        requireTargetNotSmaller(targetDocCount, currentDocCount);
        if (flushSizeInBytes <= 0) {
            throw new IllegalArgumentException("flushSizeInBytes must be positive but was [" + flushSizeInBytes + "]");
        }

        final SizeModel sizeModel = SizeModel.fromObservedSegments(observed);
        final long largestObserved = observed.stream().mapToLong(s -> s.sizeInBytes).max().getAsLong();

        List<SimSegment> segments = new ArrayList<>(observed.size());
        for (Segment segment : observed) {
            segments.add(new SimSegment(segment.sizeInBytes, segment.getNumDocs(), Math.max(0, segment.getDeletedDocs())));
        }

        final int pendingOnSource = pendingMergeCount(policy, segments);

        if (targetDocCount > currentDocCount) {
            final long docsPerFlush = Math.max(1, Math.round(flushSizeInBytes / sizeModel.bytesPerDoc(flushSizeInBytes)));
            final long flushes = Math.ceilDiv(targetDocCount - currentDocCount, docsPerFlush);
            if (flushes > MAX_SIMULATED_FLUSHES) {
                throw new IllegalArgumentException(
                    "estimating ["
                        + targetDocCount
                        + "] documents at ["
                        + docsPerFlush
                        + "] documents per flush would require ["
                        + flushes
                        + "] simulated flushes, which exceeds the limit of ["
                        + MAX_SIMULATED_FLUSHES
                        + "]; supply a larger flushSizeInBytes or a smaller targetDocCount"
                );
            }
            for (long i = 0; i < flushes; i++) {
                segments.add(new SimSegment(flushSizeInBytes, docsPerFlush, 0));
                segments = mergeToQuiescence(policy, segments, sizeModel);
            }
        }

        segments.sort(Comparator.comparingLong(SimSegment::sizeInBytes).reversed());
        return buildEstimate(policy, segments, sizeModel, flushSizeInBytes, largestObserved, pendingOnSource);
    }

    /**
     * Infers the size of a single flushed segment from the smallest observed segments.
     *
     * <p>Every flush path writes exactly one Lucene DWPT rather than the whole indexing buffer: {@link InternalEngine#writeIndexingBuffer}
     * calls {@code IndexWriter#flushNextBuffer}, and Lucene's own RAM trigger flushes only the largest pending writer. That is why the flush
     * size can be read off the segments themselves instead of being derived from heap size and core count.
     *
     * <p>The smallest segments in an at-rest shard are its most recent flushes, so this returns the median of the smallest merge-factor
     * segments. A median rather than the minimum, because the very smallest segment is often a partial flush left behind when indexing
     * stopped, and it would drag the estimate down.
     */
    public static long inferFlushSize(List<Segment> currentSegments, MergePolicy mergePolicy) {
        final TieredMergePolicy policy = requireTieredMergePolicy(mergePolicy);
        final List<Segment> observed = withKnownSize(currentSegments);
        if (observed.isEmpty()) {
            throw new IllegalArgumentException("cannot infer a flush size without at least one segment of known size");
        }
        final long[] sizes = observed.stream().mapToLong(s -> s.sizeInBytes).sorted().toArray();
        final int bottomTier = Math.min(sizes.length, mergeFactor(policy));
        return sizes[bottomTier / 2];
    }

    /**
     * Repeatedly asks the policy for merges and applies them until it reports none, which is exactly the condition under which a live shard
     * is at rest.
     */
    private static List<SimSegment> mergeToQuiescence(TieredMergePolicy policy, List<SimSegment> segments, SizeModel sizeModel) {
        List<SimSegment> current = segments;
        for (int round = 0; round < MAX_MERGE_ROUNDS; round++) {
            final MergePolicy.MergeSpecification spec = findMerges(policy, current);
            if (spec == null || spec.merges.isEmpty()) {
                return current;
            }
            current = applyMerges(current, spec, sizeModel);
        }
        throw new IllegalStateException("merge selection failed to reach a resting state within [" + MAX_MERGE_ROUNDS + "] rounds");
    }

    private static List<SimSegment> applyMerges(List<SimSegment> segments, MergePolicy.MergeSpecification spec, SizeModel sizeModel) {
        final Map<String, SimSegment> byName = new HashMap<>();
        // Names are assigned by position in toSegmentInfos, so they round-trip through the policy unchanged.
        for (int i = 0; i < segments.size(); i++) {
            byName.put(segmentName(i), segments.get(i));
        }
        final List<SimSegment> merged = new ArrayList<>();
        for (MergePolicy.OneMerge merge : spec.merges) {
            long inputBytes = 0;
            long liveDocs = 0;
            for (SegmentCommitInfo info : merge.segments) {
                final SimSegment consumed = byName.remove(info.info.name);
                assert consumed != null : "policy returned a segment we did not supply: " + info.info.name;
                inputBytes += consumed.sizeInBytes();
                liveDocs += consumed.liveDocs();
            }
            // Size the output from its live document count rather than by scaling the input bytes, so that repeated merges of the same data
            // do not compound the compression gain. Deleted documents are dropped by the merge, matching Lucene.
            final long sizeInBytes = Math.max(1, Math.round(liveDocs * sizeModel.bytesPerDoc(inputBytes)));
            merged.add(new SimSegment(sizeInBytes, liveDocs, 0));
        }
        final List<SimSegment> result = new ArrayList<>(byName.values());
        result.addAll(merged);
        return result;
    }

    private static int pendingMergeCount(TieredMergePolicy policy, List<SimSegment> segments) {
        final MergePolicy.MergeSpecification spec = findMerges(policy, segments);
        return spec == null ? 0 : spec.merges.size();
    }

    private static MergePolicy.MergeSpecification findMerges(TieredMergePolicy policy, List<SimSegment> segments) {
        try (SyntheticDirectory directory = new SyntheticDirectory()) {
            final SegmentInfos infos = toSegmentInfos(segments, directory);
            return policy.findMerges(MergeTrigger.SEGMENT_FLUSH, infos, new SyntheticMergeContext(segments));
        } catch (IOException e) {
            // Nothing in the synthetic directory or merge context performs real I/O, so this is unreachable in practice.
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Builds a {@link SegmentInfos} whose segment sizes resolve, through the usual {@code SegmentCommitInfo#sizeInBytes} to
     * {@code Directory#fileLength} path, to the modelled sizes. Going through a synthetic directory rather than subclassing the policy to
     * override {@code MergePolicy#size} means the caller's exact, fully configured policy instance is the one making the decisions, so a
     * merge setting added upstream cannot silently drop out of the model.
     */
    private static SegmentInfos toSegmentInfos(List<SimSegment> segments, SyntheticDirectory directory) {
        final SegmentInfos infos = new SegmentInfos(Version.LATEST.major);
        for (int i = 0; i < segments.size(); i++) {
            final SimSegment segment = segments.get(i);
            final String name = segmentName(i);
            final String file = IndexFileNames.segmentFileName(name, "", "est");
            directory.setFileLength(file, segment.sizeInBytes());
            final SegmentInfo info = new SegmentInfo(
                directory,
                Version.LATEST,
                Version.LATEST,
                name,
                Math.toIntExact(segment.maxDoc()),
                false,
                false,
                Codec.getDefault(),
                Map.of(),
                new byte[StringHelper.ID_LENGTH],
                Map.of(),
                null
            );
            info.setFiles(Set.of(file));
            // All generations are -1 so that files() returns only the synthetic file above.
            infos.add(new SegmentCommitInfo(info, Math.toIntExact(segment.delDocs()), 0, -1, -1, -1, new byte[StringHelper.ID_LENGTH]));
        }
        return infos;
    }

    private static ShardEstimate buildEstimate(
        TieredMergePolicy policy,
        List<SimSegment> segments,
        SizeModel sizeModel,
        long flushSizeInBytes,
        long largestObserved,
        int pendingOnSource
    ) {
        final List<EstimatedSegment> estimated = new ArrayList<>(segments.size());
        long totalSize = 0;
        long totalDocs = 0;
        for (SimSegment segment : segments) {
            estimated.add(new EstimatedSegment(segment.sizeInBytes(), segment.liveDocs(), segment.delDocs()));
            totalSize += segment.sizeInBytes();
            totalDocs += segment.liveDocs();
        }
        final long largestPredicted = segments.isEmpty() ? 0 : segments.get(0).sizeInBytes();
        return new ShardEstimate(
            List.copyOf(estimated),
            totalSize,
            totalDocs,
            summarizeTiers(policy, segments),
            sizeModel.bytesPerDoc(flushSizeInBytes),
            sizeModel.bytesPerDoc(largestObserved) / sizeModel.bytesPerDoc(flushSizeInBytes),
            flushSizeInBytes,
            assessConfidence(largestPredicted, largestObserved, pendingOnSource)
        );
    }

    private static Confidence assessConfidence(long largestPredicted, long largestObserved, int pendingOnSource) {
        if (pendingOnSource > 0) {
            return new Confidence(
                ConfidenceLevel.LOW,
                "the source index is not at rest ["
                    + pendingOnSource
                    + "] merges are still outstanding, so both the size model and the inferred flush size may be unrepresentative"
            );
        }
        final double reach = (double) largestPredicted / largestObserved;
        if (reach <= 2.0) {
            return new Confidence(ConfidenceLevel.HIGH, "the largest predicted segment is within the range of observed segment sizes");
        }
        if (reach <= 10.0) {
            return new Confidence(
                ConfidenceLevel.MEDIUM,
                "the largest predicted segment is " + Math.round(reach) + "x the largest observed segment"
            );
        }
        return new Confidence(
            ConfidenceLevel.LOW,
            "the largest predicted segment is "
                + Math.round(reach)
                + "x the largest observed segment, so bytes-per-document is extrapolated well outside the observed range"
        );
    }

    private static List<TierSummary> summarizeTiers(TieredMergePolicy policy, List<SimSegment> segments) {
        final long floorBytes = Math.max(1, (long) (policy.getFloorSegmentMB() * 1024 * 1024));
        final double tierRatio = Math.log(mergeFactor(policy));
        final Map<Integer, List<SimSegment>> byTier = new LinkedHashMap<>();
        for (SimSegment segment : segments) {
            final int tier = segment.sizeInBytes() <= floorBytes
                ? 0
                : (int) Math.floor(Math.log((double) segment.sizeInBytes() / floorBytes) / tierRatio);
            byTier.computeIfAbsent(tier, t -> new ArrayList<>()).add(segment);
        }
        final List<TierSummary> tiers = new ArrayList<>(byTier.size());
        for (Map.Entry<Integer, List<SimSegment>> entry : byTier.entrySet()) {
            final List<SimSegment> tierSegments = entry.getValue();
            long min = Long.MAX_VALUE;
            long max = 0;
            long totalSize = 0;
            long totalDocs = 0;
            for (SimSegment segment : tierSegments) {
                min = Math.min(min, segment.sizeInBytes());
                max = Math.max(max, segment.sizeInBytes());
                totalSize += segment.sizeInBytes();
                totalDocs += segment.liveDocs();
            }
            tiers.add(new TierSummary(entry.getKey(), min, max, tierSegments.size(), totalSize, totalDocs));
        }
        tiers.sort(Comparator.comparingInt(TierSummary::tier).reversed());
        return List.copyOf(tiers);
    }

    private static TieredMergePolicy requireTieredMergePolicy(MergePolicy mergePolicy) {
        if (mergePolicy instanceof TieredMergePolicy tiered) {
            return tiered;
        }
        throw new IllegalArgumentException(
            "segment estimation is only supported for ["
                + TieredMergePolicy.class.getSimpleName()
                + "] but got ["
                + mergePolicy.getClass().getSimpleName()
                + "]; an index with a @timestamp field uses LogByteSizeMergePolicy unless index.merge.policy.type is set to [tiered]"
        );
    }

    private static void requireTargetNotSmaller(long targetDocCount, long currentDocCount) {
        if (targetDocCount < currentDocCount) {
            throw new IllegalArgumentException(
                "targetDocCount [" + targetDocCount + "] must not be below the current document count [" + currentDocCount + "]"
            );
        }
    }

    private static List<Segment> withKnownSize(List<Segment> segments) {
        // Segment#sizeInBytes stays at -1 when it could not be read, and a maxDoc of zero carries no information for the size model.
        return segments.stream().filter(s -> s.sizeInBytes > 0 && s.getNumDocs() + Math.max(0, s.getDeletedDocs()) > 0).toList();
    }

    private static long liveDocs(List<Segment> segments) {
        return segments.stream().filter(s -> s.getNumDocs() > 0).mapToLong(Segment::getNumDocs).sum();
    }

    private static int mergeFactor(TieredMergePolicy policy) {
        return (int) Math.min(policy.getMaxMergeAtOnce(), policy.getSegmentsPerTier());
    }

    private static String segmentName(int ordinal) {
        return "_" + Integer.toString(ordinal, Character.MAX_RADIX);
    }

    /**
     * A modelled segment. Sizes and document counts are the two quantities the merge policy reasons about.
     */
    private record SimSegment(long sizeInBytes, long liveDocs, long delDocs) {
        long maxDoc() {
            return liveDocs + delDocs;
        }
    }

    /**
     * Bytes per document as a function of segment size, interpolated from the source index's own segments.
     *
     * <p>Bytes per document falls as segments grow, because stored field compression blocks, term dictionaries and doc values encodings all
     * amortise better over more documents. An index that is already at rest conveniently exposes several points on that curve at once, from
     * flush-sized segments up to whatever its top tier holds. Outside the observed range the nearest endpoint is held constant rather than
     * extrapolated, so the model degrades to a flat assumption instead of running away.
     */
    private static final class SizeModel {
        // TODO: Does the size model need to be segmented by tier?

        private final long[] sizes;
        private final double[] bytesPerDoc;

        private SizeModel(long[] sizes, double[] bytesPerDoc) {
            this.sizes = sizes;
            this.bytesPerDoc = bytesPerDoc;
        }

        static SizeModel fromObservedSegments(List<Segment> observed) {
            // Average the observations at each distinct size so that a tier holding many equally sized segments does not dominate.
            final Map<Long, double[]> sumAndCount = new HashMap<>();
            for (Segment segment : observed) {
                final long maxDoc = segment.getNumDocs() + Math.max(0, segment.getDeletedDocs());
                final double[] accumulator = sumAndCount.computeIfAbsent(segment.sizeInBytes, s -> new double[2]);
                accumulator[0] += (double) segment.sizeInBytes / maxDoc;
                accumulator[1]++;
            }
            final long[] sizes = sumAndCount.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
            final double[] bytesPerDoc = new double[sizes.length];
            for (int i = 0; i < sizes.length; i++) {
                final double[] accumulator = sumAndCount.get(sizes[i]);
                bytesPerDoc[i] = accumulator[0] / accumulator[1];
            }
            return new SizeModel(sizes, bytesPerDoc);
        }

        double bytesPerDoc(long sizeInBytes) {
            if (sizeInBytes <= sizes[0]) {
                return bytesPerDoc[0];
            }
            final int last = sizes.length - 1;
            if (sizeInBytes >= sizes[last]) {
                return bytesPerDoc[last];
            }
            int upper = 1;
            while (sizes[upper] < sizeInBytes) {
                upper++;
            }
            // Interpolate on a log scale: segment sizes span orders of magnitude, so a linear weighting would ignore the smaller tiers.
            final double lowerLog = Math.log(sizes[upper - 1]);
            final double weight = (Math.log(sizeInBytes) - lowerLog) / (Math.log(sizes[upper]) - lowerLog);
            return bytesPerDoc[upper - 1] + weight * (bytesPerDoc[upper] - bytesPerDoc[upper - 1]);
        }
    }

    /**
     * Supplies file lengths to {@code SegmentCommitInfo#sizeInBytes} and nothing else. Every other operation is unreachable: the merge
     * policy only ever asks a directory how long a file is.
     */
    private static final class SyntheticDirectory extends Directory {

        private final Map<String, Long> fileLengths = new HashMap<>();

        void setFileLength(String name, long length) {
            fileLengths.put(name, length);
        }

        @Override
        public long fileLength(String name) throws IOException {
            final Long length = fileLengths.get(name);
            if (length == null) {
                throw new IOException("no such synthetic file [" + name + "]");
            }
            return length;
        }

        @Override
        public String[] listAll() {
            return fileLengths.keySet().toArray(String[]::new);
        }

        @Override
        public Set<String> getPendingDeletions() {
            return Set.of();
        }

        @Override
        public void close() {}

        @Override
        public void deleteFile(String name) {
            throw unsupported();
        }

        @Override
        public IndexOutput createOutput(String name, IOContext context) {
            throw unsupported();
        }

        @Override
        public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) {
            throw unsupported();
        }

        @Override
        public void sync(Collection<String> names) {
            throw unsupported();
        }

        @Override
        public void syncMetaData() {
            throw unsupported();
        }

        @Override
        public void rename(String source, String dest) {
            throw unsupported();
        }

        @Override
        public IndexInput openInput(String name, IOContext context) {
            throw unsupported();
        }

        @Override
        public Lock obtainLock(String name) {
            throw unsupported();
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("synthetic directory only supports fileLength");
        }
    }

    /**
     * Reports the modelled delete counts to the policy. No segments are reported as merging: the estimate describes the resting state, so
     * by construction nothing is in flight.
     */
    private static final class SyntheticMergeContext implements MergePolicy.MergeContext {

        private final Map<String, SimSegment> byName = new HashMap<>();

        SyntheticMergeContext(List<SimSegment> segments) {
            for (int i = 0; i < segments.size(); i++) {
                byName.put(segmentName(i), segments.get(i));
            }
        }

        @Override
        public int numDeletesToMerge(SegmentCommitInfo info) {
            return numDeletedDocs(info);
        }

        @Override
        public int numDeletedDocs(SegmentCommitInfo info) {
            final SimSegment segment = byName.get(info.info.name);
            assert segment != null : "unknown segment [" + info.info.name + "]";
            return Math.toIntExact(segment.delDocs());
        }

        @Override
        public InfoStream getInfoStream() {
            return InfoStream.NO_OUTPUT;
        }

        @Override
        public Set<SegmentCommitInfo> getMergingSegments() {
            return Set.of();
        }
    }
}
