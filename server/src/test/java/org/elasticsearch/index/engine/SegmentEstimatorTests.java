/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.engine;

import org.apache.lucene.index.LogByteSizeMergePolicy;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.TieredMergePolicy;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.IndexSettingsModule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;

public class SegmentEstimatorTests extends ESTestCase {

    private static final long MB = 1024 * 1024;
    private static final long GB = 1024 * MB;

    /**
     * Bytes per document are chosen as powers of two throughout so that segment sizes stay exactly additive across merges. Otherwise
     * rounding pushes an eight-way merge a few hundred bytes over {@code max_merged_segment} and the geometry under test changes.
     */
    private static final int FLUSH_BYTES_PER_DOC = 256;
    private static final int MERGED_BYTES_PER_DOC = 128;

    /**
     * Uses the merge policy Elasticsearch itself would configure for a non time-based index, so the defaults under test are the ones that
     * ship: floor_segment 16mb, segments_per_tier 8, max_merge_at_once 16, max_merged_segment 5gb.
     */
    private static MergePolicy mergePolicy(Settings indexSettings) {
        final IndexSettings settings = IndexSettingsModule.newIndexSettings("test", indexSettings);
        final MergePolicy policy = settings.getMergePolicy(false);
        assertThat(policy, instanceOf(TieredMergePolicy.class));
        return policy;
    }

    private static MergePolicy defaultMergePolicy() {
        return mergePolicy(Settings.EMPTY);
    }

    private static MergePolicy mergePolicyWithMaxMergedSegment(String maxMergedSegment) {
        return mergePolicy(Settings.builder().put("index.merge.policy.max_merged_segment", maxMergedSegment).build());
    }

    /**
     * Elasticsearch derives a segment's generation from its name, so names must follow Lucene's {@code _<base36>} convention.
     */
    private static Segment segment(int ordinal, long sizeInBytes, long docCount, long delDocCount) {
        final Segment segment = new Segment("_" + Integer.toString(ordinal, Character.MAX_RADIX));
        segment.sizeInBytes = sizeInBytes;
        segment.docCount = Math.toIntExact(docCount);
        segment.delDocCount = Math.toIntExact(delDocCount);
        return segment;
    }

    /** A plausible at-rest starting point: equally sized segments at a constant bytes-per-document. */
    private static List<Segment> uniformSegments(int count, long sizeInBytes) {
        final List<Segment> segments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            segments.add(segment(i, sizeInBytes, sizeInBytes / FLUSH_BYTES_PER_DOC, 0));
        }
        return segments;
    }

    private static long liveDocs(List<Segment> segments) {
        return segments.stream().mapToLong(Segment::getNumDocs).sum();
    }

    /** Feeds an estimate back in as though it were an observed index, so it can be asserted on through the public API. */
    private static List<Segment> asSegments(SegmentEstimator.ShardEstimate estimate) {
        final List<Segment> segments = new ArrayList<>(estimate.segments().size());
        for (int i = 0; i < estimate.segments().size(); i++) {
            final SegmentEstimator.EstimatedSegment predicted = estimate.segments().get(i);
            segments.add(segment(i, predicted.sizeInBytes(), predicted.docCount(), predicted.delDocCount()));
        }
        return segments;
    }

    public void testTargetEqualToCurrentReturnsObservedSegments() {
        final List<Segment> observed = uniformSegments(5, 32 * MB);

        final SegmentEstimator.ShardEstimate estimate = SegmentEstimator.estimateShard(observed, liveDocs(observed), defaultMergePolicy());

        assertThat(estimate.segmentCount(), equalTo(observed.size()));
        assertThat(estimate.totalDocCount(), equalTo(liveDocs(observed)));
        assertThat(estimate.totalSizeInBytes(), equalTo(5 * 32 * MB));
    }

    /**
     * The contract the estimator promises is that the merge policy would request no further merges on the result. Asserting that, rather
     * than a hard-coded segment count, keeps this test meaningful across Lucene merge policy changes.
     */
    public void testEstimateIsAtRest() {
        final List<Segment> observed = uniformSegments(randomIntBetween(2, 8), randomFrom(16L * MB, 64 * MB, 256 * MB));
        final long targetDocCount = liveDocs(observed) * randomIntBetween(2, 100);

        final SegmentEstimator.ShardEstimate estimate = SegmentEstimator.estimateShard(observed, targetDocCount, defaultMergePolicy());

        // Round-tripping the estimate back through the estimator reports whether the policy still wants to merge it.
        final SegmentEstimator.ShardEstimate roundTrip = SegmentEstimator.estimateShard(
            asSegments(estimate),
            estimate.totalDocCount(),
            defaultMergePolicy()
        );
        assertThat(roundTrip.confidence().explanation(), not(containsString("not at rest")));
    }

    public void testLargerTargetNeverShrinksTheShard() {
        final List<Segment> observed = uniformSegments(4, 64 * MB);
        final long currentDocCount = liveDocs(observed);

        long previousSize = 0;
        for (int multiplier : new int[] { 1, 2, 10, 50, 200 }) {
            final SegmentEstimator.ShardEstimate estimate = SegmentEstimator.estimateShard(
                observed,
                currentDocCount * multiplier,
                defaultMergePolicy()
            );
            assertThat(estimate.totalSizeInBytes(), greaterThanOrEqualTo(previousSize));
            previousSize = estimate.totalSizeInBytes();
        }
    }

    public void testNoSegmentExceedsMaxMergedSegment() {
        final long maxMergedSegment = 256 * MB;
        final MergePolicy policy = mergePolicyWithMaxMergedSegment(maxMergedSegment + "b");
        final List<Segment> observed = uniformSegments(4, 8 * MB);

        final SegmentEstimator.ShardEstimate estimate = SegmentEstimator.estimateShard(observed, liveDocs(observed) * 500, policy);

        for (SegmentEstimator.EstimatedSegment predicted : estimate.segments()) {
            assertThat(predicted.sizeInBytes(), lessThanOrEqualTo(maxMergedSegment));
        }
    }

    public void testRaisingMaxMergedSegmentGrowsTheTopTier() {
        final List<Segment> observed = uniformSegments(4, 8 * MB);
        final long targetDocCount = liveDocs(observed) * 500;

        final SegmentEstimator.ShardEstimate small = SegmentEstimator.estimateShard(
            observed,
            targetDocCount,
            mergePolicyWithMaxMergedSegment("128mb")
        );
        final SegmentEstimator.ShardEstimate large = SegmentEstimator.estimateShard(
            observed,
            targetDocCount,
            mergePolicyWithMaxMergedSegment("1gb")
        );

        assertThat(large.segments().get(0).sizeInBytes(), greaterThan(small.segments().get(0).sizeInBytes()));
        assertThat(large.segmentCount(), lessThan(small.segmentCount()));
    }

    /**
     * Pins the bin-packing behaviour that makes flush size a first-order input: a merge closes early once the next input would cross
     * {@code max_merged_segment}, so a flush size that does not divide the cap leaves a permanent shortfall in the top tier.
     */
    public void testFlushSizeChangesTheEffectiveMaxSegmentSize() {
        final MergePolicy policy = mergePolicyWithMaxMergedSegment("1gb");
        final List<Segment> observed = uniformSegments(4, 8 * MB);
        final long targetDocCount = liveDocs(observed) * 2000;

        // Eight 128mb flushes merge to exactly 1gb; 96mb flushes cannot reach the cap without crossing it, so they stop short.
        final SegmentEstimator.ShardEstimate even = SegmentEstimator.estimateShard(observed, targetDocCount, policy, 128 * MB);
        final SegmentEstimator.ShardEstimate uneven = SegmentEstimator.estimateShard(observed, targetDocCount, policy, 96 * MB);

        assertThat(even.segments().get(0).sizeInBytes(), equalTo(GB));
        assertThat(uneven.segments().get(0).sizeInBytes(), lessThan(GB));
        assertThat(uneven.assumedFlushSizeInBytes(), equalTo(96 * MB));
    }

    public void testTimeBasedIndexIsRejected() {
        final IndexSettings settings = IndexSettingsModule.newIndexSettings("test", Settings.EMPTY);
        final MergePolicy timeBased = settings.getMergePolicy(true);
        assertThat(timeBased, instanceOf(LogByteSizeMergePolicy.class));

        final List<Segment> observed = uniformSegments(4, 64 * MB);
        final IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> SegmentEstimator.estimateShard(observed, liveDocs(observed) * 2, timeBased)
        );
        assertThat(e.getMessage(), containsString("index.merge.policy.type"));
    }

    public void testTargetBelowCurrentIsRejected() {
        final List<Segment> observed = uniformSegments(4, 64 * MB);

        final IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> SegmentEstimator.estimateShard(observed, liveDocs(observed) - 1, defaultMergePolicy())
        );
        assertThat(e.getMessage(), containsString("must not be below the current document count"));
    }

    public void testInferredFlushSizeIsTheMedianOfTheSmallestSegments() {
        // Two large merged segments plus a bottom tier of 32mb flushes: the flush size must come from the bottom tier.
        final List<Segment> observed = new ArrayList<>(uniformSegments(6, 32 * MB));
        observed.add(segment(6, 2 * GB, 2 * GB / MERGED_BYTES_PER_DOC, 0));
        observed.add(segment(7, 2 * GB, 2 * GB / MERGED_BYTES_PER_DOC, 0));

        assertThat(SegmentEstimator.inferFlushSize(observed, defaultMergePolicy()), equalTo(32 * MB));
    }

    /**
     * Bytes per document falls as segments grow, and an at-rest index exposes several points on that curve at once. The estimator should
     * read the gain off the source index rather than assuming one.
     */
    public void testMergeRecoveryFactorIsMeasuredFromObservedSegments() {
        final List<Segment> observed = new ArrayList<>(uniformSegments(6, 32 * MB));
        observed.add(segment(6, 2 * GB, 2 * GB / MERGED_BYTES_PER_DOC, 0));

        final SegmentEstimator.ShardEstimate estimate = SegmentEstimator.estimateShard(
            observed,
            liveDocs(observed) * 4,
            defaultMergePolicy(),
            32 * MB
        );

        assertThat(estimate.observedBytesPerDoc(), closeTo(FLUSH_BYTES_PER_DOC, 1.0));
        assertThat(estimate.mergeRecoveryFactor(), closeTo((double) MERGED_BYTES_PER_DOC / FLUSH_BYTES_PER_DOC, 0.01));
    }

    public void testEstimateDistributesDocumentsAcrossShardsPreservingSkew() {
        final Index index = new Index("test", "uuid");
        final ShardId small = new ShardId(index, 0);
        final ShardId large = new ShardId(index, 1);
        final Map<ShardId, List<Segment>> shards = new LinkedHashMap<>();
        shards.put(small, uniformSegments(2, 64 * MB));
        shards.put(large, uniformSegments(6, 64 * MB));

        final long currentTotal = shards.values().stream().flatMap(List::stream).mapToLong(Segment::getNumDocs).sum();
        final Map<ShardId, SegmentEstimator.ShardEstimate> estimates = SegmentEstimator.estimate(
            shards,
            currentTotal * 10,
            defaultMergePolicy()
        );

        assertThat(estimates.size(), equalTo(2));
        // The second shard starts with three times the documents, so it should end with roughly three times as many.
        final double ratio = (double) estimates.get(large).totalDocCount() / estimates.get(small).totalDocCount();
        assertThat(ratio, closeTo(3.0, 0.2));
    }
}
