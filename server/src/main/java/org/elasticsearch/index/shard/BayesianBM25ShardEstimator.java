/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.shard;

import org.apache.lucene.document.FeatureField;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BayesianScoreEstimator;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.PerFieldSimilarityWrapper;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.search.similarities.Similarity.SimScorer;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.mapper.NestedObjectMapper;
import org.elasticsearch.index.mapper.TextFieldMapper;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper.DenseVectorFieldType;
import org.elasticsearch.index.mapper.vectors.SparseVectorFieldMapper.SparseVectorFieldType;
import org.elasticsearch.index.mapper.vectors.XFeatureField;
import org.elasticsearch.index.search.ESToParentBlockJoinQuery;
import org.elasticsearch.index.similarity.SimilarityService;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.lucene.similarity.LegacyBM25Similarity;
import org.elasticsearch.search.vectors.VectorData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Estimates Bayesian BM25 parameters for the full-text fields of a shard whenever a refresh makes a newly merged segment visible.
 * Text fields are calibrated in units of their mean token idf, with the midpoint fitted against the dense vector fields' nearest neighbors
 * of sampled documents when there are any, falling back to Lucene's {@link BayesianScoreEstimator}.
 * It also estimates sigmoid calibration parameters for the dense and sparse vector fields of semantic fields, so that their scores can be
 * fused with the calibrated BM25 scores; dense fields use a two-pool logistic fit over sampled vectors' neighbors and random documents,
 * and sparse fields use the median and spread of sampled chunks' neighbor scores.
 * The estimate runs asynchronously, at most one at a time, against the shard-wide searcher so that it sees the same term statistics as
 * queries do.
 */
class BayesianBM25ShardEstimator implements ReferenceManager.RefreshListener {

    private static final Logger logger = LogManager.getLogger(BayesianBM25ShardEstimator.class);

    /** Number of indexed vectors sampled as pseudo-queries when calibrating a vector field. */
    private static final int VECTOR_SAMPLES = 1000;
    /** Number of nearest neighbors, excluding the sampled vector's own document, scored per pseudo-query. */
    private static final int VECTOR_NEIGHBORS = 1000;
    /** Candidates per pseudo-query, following the kNN query default of 1.5 * k. */
    private static final int VECTOR_NUM_CANDIDATES = Math.round(1.5f * (VECTOR_NEIGHBORS + 1));
    private static final long VECTOR_SAMPLE_SEED = 42;
    /** Neighbor ranks {@code [0, VECTOR_POSITIVES)} are the positives of the two-pool dense vector calibration. */
    private static final int VECTOR_POSITIVES = 50;
    /** Neighbor ranks {@code [VECTOR_HARD_NEGATIVES_FROM, VECTOR_NEIGHBORS)} are the hard negatives, which set the sigmoid slope. */
    private static final int VECTOR_HARD_NEGATIVES_FROM = 500;
    /** Number of random documents scored against every pseudo-query as negatives, which set the sigmoid midpoint. */
    private static final int VECTOR_RANDOM_SAMPLES = 1000;
    /** Differs from {@link #VECTOR_SAMPLE_SEED} so that the random documents are not the pseudo-queries themselves. */
    private static final long VECTOR_RANDOM_SEED = 43;

    /** Number of chunks whose token vectors are sampled as pseudo-queries when calibrating a sparse vector field. */
    private static final int SPARSE_SAMPLES = 200;
    private static final long SPARSE_SAMPLE_SEED = 42;

    private static final long TEXT_SAMPLE_SEED = 42;
    /**
     * Text calibration constants in units of the field's mean token idf {@code mu}: {@code alpha = TEXT_SLOPE / mu} and
     * {@code beta = TEXT_MIDPOINT * mu}. They map a score of zero to 0.05 and the score of a query of 10 typical tokens matching fully to
     * 0.95, i.e. {@code 2 ln 19 / 10} and {@code 10 / 2}. {@code TEXT_MIDPOINT} is the fallback when the midpoint cannot be fitted
     * against a dense vector field's neighbors, see {@link #textMidpoint}.
     */
    private static final double TEXT_SLOPE = 2 * Math.log(19) / 10;
    private static final double TEXT_MIDPOINT = 5;
    /** Number of a source document's most significant terms that form its lexical pseudo-query when fitting the text midpoint. */
    private static final int TEXT_QUERY_TERMS = 10;
    /** Number of random matching documents per pseudo-query that are the negatives of the text midpoint fit. */
    private static final int TEXT_RANDOM_NEGATIVES = 500;
    private static final long TEXT_RANDOM_SEED = 43;
    /** Saturation used to rank a document's terms; the document's length is the same for all of its terms, so it is left out. */
    private static final float TERM_RANK_K1 = 1.2f;

    private final ShardId shardId;
    private final Supplier<Engine> engineSupplier;
    private final MapperService mapperService;
    private final Executor executor;
    private final int nSamples;
    private final int tokensPerQuery;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile BayesianBM25ShardParameters parameters;

    BayesianBM25ShardEstimator(
        ShardId shardId,
        Supplier<Engine> engineSupplier,
        MapperService mapperService,
        Executor executor,
        int nSamples,
        int tokensPerQuery
    ) {
        this.shardId = shardId;
        this.engineSupplier = engineSupplier;
        this.mapperService = mapperService;
        this.executor = executor;
        this.nSamples = nSamples;
        this.tokensPerQuery = tokensPerQuery;
    }

    BayesianBM25ShardParameters parameters() {
        return parameters;
    }

    @Override
    public void beforeRefresh() {}

    @Override
    public void afterRefresh(boolean didRefresh) {
        if (didRefresh == false) {
            return;
        }
        try (Engine.Searcher searcher = engineSupplier.get().acquireSearcher("bayesian_bm25_trigger")) {
            if (searcher.getIndexReader().maxDoc() == 0) {
                return;
            }
            final BayesianBM25ShardParameters current = parameters;
            if (current == null || current.segmentNames().containsAll(mergedSegmentNames(searcher)) == false) {
                schedule();
            }
        } catch (AlreadyClosedException ignored) {
            // engine is closed, nothing to estimate
        }
    }

    private void schedule() {
        pending.set(true);
        if (running.compareAndSet(false, true)) {
            try {
                executor.execute(this::runPending);
            } catch (RuntimeException e) {
                running.set(false);
                logger.debug("[{}] could not schedule Bayesian BM25 estimation", shardId, e);
            }
        }
    }

    private void runPending() {
        try {
            while (pending.getAndSet(false)) {
                estimate();
            }
        } catch (AlreadyClosedException ignored) {
            // engine is closed, nothing to estimate
        } finally {
            running.set(false);
            if (pending.get()) {
                schedule();
            }
        }
    }

    private void estimate() {
        try (Engine.Searcher searcher = engineSupplier.get().acquireSearcher("bayesian_bm25_estimate")) {
            final Set<String> segmentNames = mergedSegmentNames(searcher);
            final Map<String, BayesianScoreEstimator.Parameters> byField = new HashMap<>();
            // Vector fields first: their sampled documents' nearest neighbors anchor the text fields' midpoints
            final Map<Integer, Set<Integer>> vectorNeighbors = new HashMap<>();
            for (MappedFieldType fieldType : semanticEmbeddingFields()) {
                try {
                    BayesianScoreEstimator.Parameters vectorParameters = switch (fieldType) {
                        case DenseVectorFieldType dense -> estimateVector(searcher, dense, vectorNeighbors);
                        case SparseVectorFieldType sparse -> estimateSparse(searcher, sparse);
                        default -> throw new AssertionError("unexpected embedding field type [" + fieldType.typeName() + "]");
                    };
                    if (vectorParameters != null) {
                        byField.put(fieldType.name(), vectorParameters);
                    }
                } catch (IOException | RuntimeException e) {
                    logger.warn("[{}] failed to estimate vector calibration parameters for field [{}]", shardId, fieldType.name(), e);
                }
            }
            for (String field : fullTextFields(FieldInfos.getMergedFieldInfos(searcher.getIndexReader()))) {
                try {
                    BayesianScoreEstimator.Parameters textParameters = estimateText(searcher, field, vectorNeighbors);
                    if (textParameters == null) {
                        logger.debug("[{}] falling back to Lucene's Bayesian BM25 estimator for field [{}]", shardId, field);
                        textParameters = BayesianScoreEstimator.estimate(searcher, field, nSamples, tokensPerQuery, TEXT_SAMPLE_SEED);
                    }
                    byField.put(field, textParameters);
                } catch (IOException | RuntimeException e) {
                    logger.warn("[{}] failed to estimate Bayesian BM25 parameters for field [{}]", shardId, field, e);
                }
            }
            parameters = new BayesianBM25ShardParameters(segmentNames, byField);
        }
    }

    /** Returns, for one leaf, an iterator over the documents that have a value for a field, or {@code null} if none do. */
    @FunctionalInterface
    private interface DocsWithValue {
        DocIdSetIterator iterator(LeafReaderContext leaf) throws IOException;
    }

    /**
     * Samples up to {@code maxSamples} distinct live documents across the shard among those {@code docsWithValue} returns.
     *
     * @return the sorted, leaf-level doc ids sampled in each leaf, or {@code null} if no live document has a value
     */
    private static int[][] sampleDocs(List<LeafReaderContext> leaves, DocsWithValue docsWithValue, int maxSamples, long seed)
        throws IOException {
        final long[] leafStarts = new long[leaves.size() + 1];
        for (int i = 0; i < leaves.size(); i++) {
            leafStarts[i + 1] = leafStarts[i] + countLiveDocs(leaves.get(i), docsWithValue);
        }
        final long total = leafStarts[leaves.size()];
        if (total == 0) {
            return null;
        }

        final int sampleCount = (int) Math.min(maxSamples, total);
        final long[] sampled = new Random(seed).longs(0, total).distinct().limit(sampleCount).sorted().toArray();
        final int[][] perLeaf = new int[leaves.size()][];
        int next = 0;
        for (int i = 0; i < leaves.size(); i++) {
            int end = next;
            while (end < sampleCount && sampled[end] < leafStarts[i + 1]) {
                end++;
            }
            perLeaf[i] = end == next
                ? new int[0]
                : sampledDocs(leaves.get(i), docsWithValue, Arrays.copyOfRange(sampled, next, end), leafStarts[i]);
            next = end;
        }
        return perLeaf;
    }

    /** Counts the live documents of the leaf that {@code docsWithValue} returns. */
    private static long countLiveDocs(LeafReaderContext leaf, DocsWithValue docsWithValue) throws IOException {
        final DocIdSetIterator docs = docsWithValue.iterator(leaf);
        if (docs == null) {
            return 0;
        }
        final Bits liveDocs = leaf.reader().getLiveDocs();
        long count = 0;
        for (int doc = docs.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = docs.nextDoc()) {
            if (liveDocs == null || liveDocs.get(doc)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Resolves sorted shard-wide sample positions to the leaf's doc ids, counting only the live documents {@code docsWithValue}
     * returns, in the same order as {@link #countLiveDocs}.
     */
    private static int[] sampledDocs(LeafReaderContext leaf, DocsWithValue docsWithValue, long[] positions, long leafStart)
        throws IOException {
        final DocIdSetIterator iterator = docsWithValue.iterator(leaf);
        final Bits liveDocs = leaf.reader().getLiveDocs();
        final int[] docs = new int[positions.length];
        int next = 0;
        long position = leafStart;
        for (int doc = iterator.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS && next < positions.length; doc = iterator.nextDoc()) {
            if (liveDocs != null && liveDocs.get(doc) == false) {
                continue;
            }
            if (position == positions[next]) {
                docs[next++] = doc;
            }
            position++;
        }
        assert next == positions.length : "resolved " + next + " of " + positions.length + " sampled documents";
        return docs;
    }

    private record WeightedTerm(BytesRef term, float weight) {}

    /** Weighs a term of a document from its term frequency, to rank the document's terms. */
    @FunctionalInterface
    private interface TermWeigher {
        float weight(BytesRef term, int freq) throws IOException;
    }

    /**
     * Finds the top {@code limit} terms of each of the leaf's sorted documents in one pass over the field's terms, ranked by
     * {@code weigher}. Each queue is a min-heap on the weight.
     */
    private static List<PriorityQueue<WeightedTerm>> collectTerms(
        LeafReaderContext leaf,
        String field,
        int[] docs,
        int limit,
        TermWeigher weigher
    ) throws IOException {
        final List<PriorityQueue<WeightedTerm>> topTerms = new ArrayList<>(docs.length);
        for (int i = 0; i < docs.length; i++) {
            topTerms.add(new PriorityQueue<>(Math.min(limit, 64) + 1, Comparator.comparingDouble(WeightedTerm::weight)));
        }
        final Terms terms = leaf.reader().terms(field);
        if (terms == null) {
            return topTerms;
        }
        final TermsEnum termsEnum = terms.iterator();
        PostingsEnum postings = null;
        for (BytesRef term = termsEnum.next(); term != null; term = termsEnum.next()) {
            postings = termsEnum.postings(postings, PostingsEnum.FREQS);
            for (int i = 0; i < docs.length; i++) {
                int doc = postings.docID();
                if (doc < docs[i]) {
                    doc = postings.advance(docs[i]);
                }
                if (doc == DocIdSetIterator.NO_MORE_DOCS) {
                    break;
                }
                if (doc != docs[i]) {
                    continue;
                }
                final float weight = weigher.weight(term, postings.freq());
                final PriorityQueue<WeightedTerm> queue = topTerms.get(i);
                if (queue.size() < limit) {
                    queue.add(new WeightedTerm(BytesRef.deepCopyOf(term), weight));
                } else if (weight > queue.peek().weight()) {
                    queue.poll();
                    queue.add(new WeightedTerm(BytesRef.deepCopyOf(term), weight));
                }
            }
        }
        return topTerms;
    }

    /**
     * Returns the indexed embedding fields that hold the chunk embeddings of semantic fields, i.e. the nested float dense vector and
     * sparse vector fields under an inference field.
     */
    private List<MappedFieldType> semanticEmbeddingFields() {
        final List<MappedFieldType> fields = new ArrayList<>();
        if (mapperService == null) {
            return fields;
        }
        final MappingLookup lookup = mapperService.mappingLookup();
        for (String inferenceField : lookup.inferenceFields().keySet()) {
            for (String name : lookup.getMatchingFieldNames(inferenceField + ".*")) {
                if (lookup.nestedLookup().getNestedParent(name) == null) {
                    continue;
                }
                MappedFieldType mappedFieldType = lookup.getFieldType(name);
                if (mappedFieldType instanceof SparseVectorFieldType sparseFieldType && sparseFieldType.isSearchable()) {
                    fields.add(sparseFieldType);
                } else if (mappedFieldType instanceof DenseVectorFieldType fieldType && fieldType.isSearchable()) {
                    switch (fieldType.getElementType()) {
                        case FLOAT, BFLOAT16 -> fields.add(fieldType);
                        case BYTE, BIT -> logger.debug(
                            "[{}] skipping vector calibration for field [{}] with element type [{}]",
                            shardId,
                            name,
                            fieldType.getElementType()
                        );
                    }
                }
            }
        }
        return fields;
    }

    /**
     * Calibrates a text field in units of {@code mu}, the largest score a token drawn at random from the field's text can contribute, so
     * that the calibration does not change when a field's scores are all scaled, and a typical matching token is worth the same in every
     * field. The unit has to be measured in the field similarity's own scores: Elasticsearch's default {@link LegacyBM25Similarity}
     * scores {@code k1 + 1} times higher than Lucene's {@link BM25Similarity}. A token's largest contribution is its BM25 idf times that
     * similarity's constant factor, so {@code mu} is the token-weighted mean idf times the factor.
     * No base rate: log-odds fusion adds each clause's log-odds, so a prior in every clause would be counted once per clause.
     * The midpoint is fitted against the dense vector fields' neighbors when there are any, see {@link #textMidpoint}.
     *
     * @param vectorNeighbors the nearest neighbors of the dense vector fields' sampled documents, by shard doc id
     * @return the parameters, or {@code null} if the field has no terms on this shard or is not scored with BM25
     */
    private BayesianScoreEstimator.Parameters estimateText(IndexSearcher searcher, String field, Map<Integer, Set<Integer>> vectorNeighbors)
        throws IOException {
        Similarity similarity = searcher.getSimilarity();
        if (similarity instanceof PerFieldSimilarityWrapper perField) {
            // the per-field similarity comes wrapped in a guard against zero norms
            similarity = SimilarityService.unwrap(perField.get(field));
        }
        if (similarity instanceof BM25Similarity == false && similarity instanceof LegacyBM25Similarity == false) {
            return null;
        }
        final IndexReader reader = searcher.getIndexReader();
        final Terms terms = MultiTerms.getTerms(reader, field);
        final int docCount = reader.getDocCount(field);
        if (terms == null || docCount == 0) {
            return null;
        }
        double weightedIdf = 0;
        double totalTermFreq = 0;
        BytesRef referenceTerm = null;
        int referenceDocFreq = 0;
        long referenceTermFreq = 0;
        final TermsEnum termsEnum = terms.iterator();
        for (BytesRef term = termsEnum.next(); term != null; term = termsEnum.next()) {
            final int docFreq = termsEnum.docFreq();
            final long termFreq = termsEnum.totalTermFreq();
            weightedIdf += termFreq * bm25Idf(docFreq, docCount);
            totalTermFreq += termFreq;
            if (referenceTerm == null) {
                referenceTerm = BytesRef.deepCopyOf(term);
                referenceDocFreq = docFreq;
                referenceTermFreq = termFreq;
            }
        }
        if (totalTermFreq == 0 || weightedIdf <= 0) {
            return null;
        }
        // The similarity's largest score for one term, at unbounded term frequency, divided by the term's idf
        final SimScorer scorer = similarity.scorer(
            1f,
            searcher.collectionStatistics(field),
            new TermStatistics(referenceTerm, referenceDocFreq, referenceTermFreq)
        );
        final double scale = scorer.score(Float.MAX_VALUE, 1L) / bm25Idf(referenceDocFreq, docCount);
        if (Double.isFinite(scale) == false || scale <= 0) {
            return null;
        }
        final double mu = scale * weightedIdf / totalTermFreq;
        final double alpha = TEXT_SLOPE / mu;
        Double beta = vectorNeighbors.isEmpty() ? null : textMidpoint(searcher, field, vectorNeighbors, alpha);
        if (beta == null) {
            if (vectorNeighbors.isEmpty() == false) {
                logger.debug("[{}] could not fit the midpoint of field [{}] against vector neighbors, using the default", shardId, field);
            }
            beta = TEXT_MIDPOINT * mu;
        }
        return new BayesianScoreEstimator.Parameters((float) alpha, beta.floatValue(), 0f);
    }

    /**
     * Fits a text field's midpoint with its slope held at {@code alpha}: the score at which a document is as likely to be one of a
     * dense vector field's nearest neighbors of a sampled document as a random document, among the documents that match a pseudo-query
     * made of the sampled document's most significant terms. Calibrating the text field against another signal's neighbors puts it on
     * the same footing as the vector fields in the fusion.
     *
     * @return the midpoint, or {@code null} if there are no positives or negatives, or the fit is degenerate
     */
    private Double textMidpoint(IndexSearcher searcher, String field, Map<Integer, Set<Integer>> vectorNeighbors, double alpha)
        throws IOException {
        final IndexReader reader = searcher.getIndexReader();
        final List<LeafReaderContext> leaves = reader.leaves();

        // Up to nSamples sources, drawn deterministically, grouped by leaf in doc id order as collectTerms expects
        final List<Integer> sources = new ArrayList<>(vectorNeighbors.keySet());
        sources.sort(null);
        Collections.shuffle(sources, new Random(TEXT_SAMPLE_SEED));
        final List<List<Integer>> byLeaf = new ArrayList<>(leaves.size());
        for (int i = 0; i < leaves.size(); i++) {
            byLeaf.add(new ArrayList<>());
        }
        for (int source : sources.subList(0, Math.min(nSamples, sources.size()))) {
            byLeaf.get(ReaderUtil.subIndex(source, leaves)).add(source);
        }

        final TermWeigher weigher = new IdfWeigher(reader, field);
        final Random random = new Random(TEXT_RANDOM_SEED);
        float[] positives = new float[16];
        float[] negatives = new float[16];
        int positiveCount = 0;
        int negativeCount = 0;
        for (int i = 0; i < leaves.size(); i++) {
            final List<Integer> leafSources = byLeaf.get(i);
            if (leafSources.isEmpty()) {
                continue;
            }
            leafSources.sort(null);
            final LeafReaderContext leaf = leaves.get(i);
            final int[] docs = leafSources.stream().mapToInt(source -> source - leaf.docBase).toArray();
            final List<PriorityQueue<WeightedTerm>> topTerms = collectTerms(leaf, field, docs, TEXT_QUERY_TERMS, weigher);
            for (int d = 0; d < docs.length; d++) {
                final int source = leaf.docBase + docs[d];
                final Map<Integer, Float> matches = scoreMatches(searcher, field, topTerms.get(d), source);
                if (matches.isEmpty()) {
                    continue;
                }
                for (int neighbor : vectorNeighbors.get(source)) {
                    final Float score = matches.get(neighbor);
                    if (score != null) {
                        positives = ArrayUtil.grow(positives, positiveCount + 1);
                        positives[positiveCount++] = score;
                    }
                }
                final float[] matchScores = new float[matches.size()];
                int m = 0;
                for (float score : matches.values()) {
                    matchScores[m++] = score;
                }
                final int[] picked = matchScores.length <= TEXT_RANDOM_NEGATIVES
                    ? IntStream.range(0, matchScores.length).toArray()
                    : random.ints(0, matchScores.length).distinct().limit(TEXT_RANDOM_NEGATIVES).toArray();
                negatives = ArrayUtil.grow(negatives, negativeCount + picked.length);
                for (int p : picked) {
                    negatives[negativeCount++] = matchScores[p];
                }
            }
        }
        if (positiveCount == 0 || negativeCount == 0) {
            return null;
        }
        final double beta = fitMidpoint(positives, positiveCount, negatives, negativeCount, alpha);
        return Double.isFinite(beta) ? beta : null;
    }

    /**
     * Class-balanced logistic fit of the midpoint of {@code sigmoid(alpha * (score - beta))} with the slope held at {@code alpha}, so
     * that both pools weigh the same regardless of their sizes. Newton's method on {@code beta}, starting at the mean of both pools.
     */
    private static double fitMidpoint(float[] positives, int positiveCount, float[] negatives, int negativeCount, double alpha) {
        final double positiveWeight = 0.5 / positiveCount;
        final double negativeWeight = 0.5 / negativeCount;
        double beta = 0;
        for (int i = 0; i < positiveCount; i++) {
            beta += positives[i];
        }
        for (int i = 0; i < negativeCount; i++) {
            beta += negatives[i];
        }
        beta /= positiveCount + negativeCount;
        for (int iteration = 0; iteration < 100; iteration++) {
            // gradient and curvature of the weighted negative log-likelihood in beta, over both pools
            double gradient = 0;
            double curvature = 0;
            for (int i = 0; i < positiveCount; i++) {
                final double p = 1 / (1 + Math.exp(-alpha * (positives[i] - beta)));
                gradient += positiveWeight * (p - 1) * -alpha;
                curvature += positiveWeight * p * (1 - p) * alpha * alpha;
            }
            for (int i = 0; i < negativeCount; i++) {
                final double p = 1 / (1 + Math.exp(-alpha * (negatives[i] - beta)));
                gradient += negativeWeight * p * -alpha;
                curvature += negativeWeight * p * (1 - p) * alpha * alpha;
            }
            if (curvature <= 0) {
                return Double.NaN;
            }
            final double step = gradient / curvature;
            beta -= step;
            if (Math.abs(step) < 1e-10) {
                break;
            }
        }
        return beta;
    }

    /** Returns the scores of all live documents matching a disjunction of the given terms on the field, except {@code sourceDoc}. */
    private static Map<Integer, Float> scoreMatches(IndexSearcher searcher, String field, PriorityQueue<WeightedTerm> terms, int sourceDoc)
        throws IOException {
        final Map<Integer, Float> scores = new HashMap<>();
        if (terms.isEmpty()) {
            return scores;
        }
        final BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (WeightedTerm term : terms) {
            builder.add(new TermQuery(new Term(field, term.term())), BooleanClause.Occur.SHOULD);
        }
        final Weight weight = searcher.createWeight(searcher.rewrite(builder.build()), ScoreMode.COMPLETE, 1f);
        for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
            final Scorer scorer = weight.scorer(leaf);
            if (scorer == null) {
                continue;
            }
            final Bits liveDocs = leaf.reader().getLiveDocs();
            final DocIdSetIterator it = scorer.iterator();
            for (int doc = it.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = it.nextDoc()) {
                if ((liveDocs != null && liveDocs.get(doc) == false) || leaf.docBase + doc == sourceDoc) {
                    continue;
                }
                scores.put(leaf.docBase + doc, scorer.score());
            }
        }
        return scores;
    }

    /**
     * Weighs text terms by {@code idf * tf / (tf + k1)}. The shard-wide idf is computed once per term: terms arrive in order, each one
     * for every sampled document that contains it.
     */
    private static final class IdfWeigher implements TermWeigher {
        private final IndexReader reader;
        private final String field;
        private final long docCount;
        private BytesRef lastTerm;
        private float lastIdf;

        IdfWeigher(IndexReader reader, String field) throws IOException {
            this.reader = reader;
            this.field = field;
            this.docCount = Math.max(reader.getDocCount(field), 1);
        }

        @Override
        public float weight(BytesRef term, int freq) throws IOException {
            if (lastTerm == null || term.bytesEquals(lastTerm) == false) {
                lastIdf = (float) bm25Idf(reader.docFreq(new Term(field, term)), docCount);
                lastTerm = BytesRef.deepCopyOf(term);
            }
            return lastIdf * freq / (freq + TERM_RANK_K1);
        }
    }

    /** BM25's idf, as {@link BM25Similarity} computes it. */
    private static double bm25Idf(long docFreq, long docCount) {
        return Math.log(1 + (docCount - docFreq + 0.5) / (docFreq + 0.5));
    }

    /**
     * Calibrates a semantic field's vector scores by using indexed vectors as pseudo-queries: each sampled vector retrieves its nearest
     * {@link #VECTOR_NEIGHBORS} documents, excluding its own, with the same nested kNN query that searches use, and is also scored
     * against random documents. See {@link #twoPoolParameters} for how the scores become sigmoid parameters. No base rate is estimated.
     *
     * @param neighbors collects, for each sampled document, its {@link #VECTOR_POSITIVES} nearest neighbors, by shard doc id
     * @return the parameters, or {@code null} if the field has no vectors on this shard
     */
    private BayesianScoreEstimator.Parameters estimateVector(
        Engine.Searcher searcher,
        DenseVectorFieldType fieldType,
        Map<Integer, Set<Integer>> neighbors
    ) throws IOException {
        final String field = fieldType.name();
        final IndexSettings indexSettings = mapperService.getIndexSettings();
        final BitSetProducer parentFilter = mapperService.parserContext()
            .bitSetProducer(Queries.newNonNestedFilter(indexSettings.getIndexVersionCreated()));
        final List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();

        final long[] leafStarts = new long[leaves.size() + 1];
        for (int i = 0; i < leaves.size(); i++) {
            FloatVectorValues values = leaves.get(i).reader().getFloatVectorValues(field);
            leafStarts[i + 1] = leafStarts[i] + (values == null ? 0 : values.size());
        }
        final long total = leafStarts[leaves.size()];
        if (total == 0) {
            return null;
        }

        // Sample distinct vector ordinals across the shard, then read each vector and the parent document it belongs to
        final int sampleCount = (int) Math.min(VECTOR_SAMPLES, total);
        final long[] sampled = new Random(VECTOR_SAMPLE_SEED).longs(0, total).distinct().limit(sampleCount).sorted().toArray();
        final List<float[]> vectors = new ArrayList<>(sampleCount);
        final int[] sourceParents = new int[sampleCount];
        int next = 0;
        for (int i = 0; i < leaves.size() && next < sampleCount; i++) {
            if (sampled[next] >= leafStarts[i + 1]) {
                continue;
            }
            final LeafReaderContext leaf = leaves.get(i);
            final FloatVectorValues values = leaf.reader().getFloatVectorValues(field);
            final BitSet parents = parentFilter.getBitSet(leaf);
            while (next < sampleCount && sampled[next] < leafStarts[i + 1]) {
                final int ord = (int) (sampled[next] - leafStarts[i]);
                final int childDoc = values.ordToDoc(ord);
                vectors.add(values.vectorValue(ord).clone());
                sourceParents[next] = leaf.docBase + (parents == null ? childDoc : parents.nextSetBit(childDoc));
                next++;
            }
        }

        final float[] scores = new float[sampleCount * VECTOR_NEIGHBORS];
        final float[] positives = new float[sampleCount * VECTOR_POSITIVES];
        final float[] hardNegatives = new float[sampleCount * (VECTOR_NEIGHBORS - VECTOR_HARD_NEGATIVES_FROM)];
        int scoreCount = 0;
        int positiveCount = 0;
        int hardNegativeCount = 0;
        for (int i = 0; i < vectors.size(); i++) {
            final Query query = fieldType.createKnnQuery(
                VectorData.fromFloats(vectors.get(i)),
                VECTOR_NEIGHBORS + 1,
                VECTOR_NUM_CANDIDATES,
                null,
                null,
                null,
                null,
                parentFilter,
                indexSettings.getHnswFilterHeuristic(),
                indexSettings.getHnswEarlyTermination(),
                indexSettings.getPostFilterSelectivityThreshold(),
                null
            );
            // The diversifying kNN query returns the best-scoring child per parent, which is the score of the nested query with
            // score mode max. The top-docs cap matters because the exact (flat) variant ignores k.
            final TopDocs topDocs = searcher.search(query, VECTOR_NEIGHBORS + 1);
            int kept = 0;
            for (ScoreDoc hit : topDocs.scoreDocs) {
                if (kept == VECTOR_NEIGHBORS) {
                    break;
                }
                final int parent = parentOf(hit.doc, leaves, parentFilter);
                if (parent == sourceParents[i]) {
                    continue;
                }
                // hits are in descending score order, so the neighbor rank is the number of hits kept so far
                if (kept < VECTOR_POSITIVES) {
                    positives[positiveCount++] = hit.score;
                    neighbors.computeIfAbsent(sourceParents[i], source -> new HashSet<>()).add(parent);
                } else if (kept >= VECTOR_HARD_NEGATIVES_FROM) {
                    hardNegatives[hardNegativeCount++] = hit.score;
                }
                scores[scoreCount++] = hit.score;
                kept++;
            }
        }
        final float[] randomNegatives = scoreRandomDocuments(leaves, leafStarts, field, parentFilter, vectors, sourceParents);
        return twoPoolParameters(
            positives,
            positiveCount,
            hardNegatives,
            hardNegativeCount,
            randomNegatives,
            randomNegatives.length,
            scores,
            scoreCount
        );
    }

    /**
     * Scores every pseudo-query against {@link #VECTOR_RANDOM_SAMPLES} random documents, skipping its own. A document's score is the max
     * over its chunks, as the nested kNN query scores it. Vectors are compared at full precision, so on a quantized field these scores
     * differ slightly from the kNN scores of the neighbors.
     */
    private static float[] scoreRandomDocuments(
        List<LeafReaderContext> leaves,
        long[] leafStarts,
        String field,
        BitSetProducer parentFilter,
        List<float[]> queries,
        int[] queryParents
    ) throws IOException {
        // Sample vector ordinals like the pseudo-queries, then load all chunk vectors of each distinct parent document
        final long total = leafStarts[leaves.size()];
        final int sampleCount = (int) Math.min(VECTOR_RANDOM_SAMPLES, total);
        final long[] sampled = new Random(VECTOR_RANDOM_SEED).longs(0, total).distinct().limit(sampleCount).sorted().toArray();
        final List<float[][]> randomChunks = new ArrayList<>(sampleCount);
        final List<Integer> randomParents = new ArrayList<>(sampleCount);
        VectorSimilarityFunction similarity = null;
        int next = 0;
        for (int i = 0; i < leaves.size() && next < sampleCount; i++) {
            if (sampled[next] >= leafStarts[i + 1]) {
                continue;
            }
            final LeafReaderContext leaf = leaves.get(i);
            final FloatVectorValues values = leaf.reader().getFloatVectorValues(field);
            similarity = leaf.reader().getFieldInfos().fieldInfo(field).getVectorSimilarityFunction();
            final BitSet parents = parentFilter.getBitSet(leaf);
            // A copy, so that its iterator's position is ours alone: on sparse fields, iterators of one instance share it
            final FloatVectorValues own = values.copy();
            final KnnVectorValues.DocIndexIterator chunkIterator = own.iterator();
            int lastParent = -1;
            while (next < sampleCount && sampled[next] < leafStarts[i + 1]) {
                final int childDoc = values.ordToDoc((int) (sampled[next] - leafStarts[i]));
                next++;
                final int parent = parents == null ? childDoc : parents.nextSetBit(childDoc);
                if (parent == lastParent) {
                    continue; // ordinals are sorted and in doc order, so a parent's sampled chunks are adjacent
                }
                lastParent = parent;
                randomChunks.add(parentChunks(own, chunkIterator, parents, parent));
                randomParents.add(leaf.docBase + parent);
            }
        }
        if (similarity == null || randomChunks.isEmpty()) {
            return new float[0];
        }

        final float[] scores = new float[queries.size() * randomChunks.size()];
        int count = 0;
        for (int q = 0; q < queries.size(); q++) {
            final float[] query = queries.get(q);
            for (int r = 0; r < randomChunks.size(); r++) {
                if (randomParents.get(r) == queryParents[q]) {
                    continue;
                }
                float max = Float.NEGATIVE_INFINITY;
                for (float[] chunk : randomChunks.get(r)) {
                    max = Math.max(max, similarity.compare(query, chunk));
                }
                scores[count++] = max;
            }
        }
        return Arrays.copyOf(scores, count);
    }

    /**
     * Returns copies of the vectors of a parent document's chunks, given its leaf-level doc id, or of the document itself if not nested.
     * Callers pass parents in increasing doc order, with an iterator of {@code values} that nothing else moves.
     */
    private static float[][] parentChunks(FloatVectorValues values, KnnVectorValues.DocIndexIterator it, BitSet parents, int parent)
        throws IOException {
        final int firstChild = parents == null || parent == 0 ? parent : parents.prevSetBit(parent - 1) + 1;
        final int endChild = parents == null ? parent + 1 : parent;
        // advance only when behind: the previous parent's loop may already stand on this parent's first chunk
        final List<float[]> chunks = new ArrayList<>();
        for (int doc = it.docID() < firstChild ? it.advance(firstChild) : it.docID(); doc < endChild; doc = it.nextDoc()) {
            chunks.add(values.vectorValue(it.index()).clone());
        }
        return chunks.toArray(new float[0][]);
    }

    /**
     * Turns dense vector neighbor scores into sigmoid parameters with two class-balanced logistic fits of the positives (the nearest
     * neighbors): against hard negatives (farther neighbors) for {@code alpha}, and against random documents for {@code beta}. Falls back
     * to {@link #poolParameters} over all neighbor scores when a pool is empty, a fit is degenerate, or the hard negatives are separated
     * more easily than the random documents: then they are not hard, as on a shard with few documents, where the farther neighbor ranks
     * span most of the shard.
     *
     * @return the parameters, or {@code null} if there are no scores
     */
    private static BayesianScoreEstimator.Parameters twoPoolParameters(
        float[] positives,
        int positiveCount,
        float[] hardNegatives,
        int hardNegativeCount,
        float[] randomNegatives,
        int randomNegativeCount,
        float[] scores,
        int scoreCount
    ) {
        if (positiveCount > 0 && hardNegativeCount > 0 && randomNegativeCount > 0) {
            final double[] slopeFit = fitLogistic(positives, positiveCount, hardNegatives, hardNegativeCount);
            final double[] midpointFit = fitLogistic(positives, positiveCount, randomNegatives, randomNegativeCount);
            if (slopeFit != null && midpointFit != null) {
                if (slopeFit[0] <= midpointFit[0]) {
                    return new BayesianScoreEstimator.Parameters((float) slopeFit[0], (float) midpointFit[1], 0f);
                }
                logger.debug(
                    "two-pool vector hard negatives are not hard (slope [{}] against them, [{}] against random documents), "
                        + "using median and spread",
                    slopeFit[0],
                    midpointFit[0]
                );
            } else {
                logger.debug(
                    "two-pool vector fit failed (slope fit {}, midpoint fit {}), using median and spread",
                    slopeFit != null,
                    midpointFit != null
                );
            }
        } else {
            logger.debug(
                "two-pool vector pools are empty (positives {}, hard negatives {}, random negatives {}), using median and spread",
                positiveCount,
                hardNegativeCount,
                randomNegativeCount
            );
        }
        return poolParameters(scores, scoreCount);
    }

    /**
     * Fits {@code P(positive | score) = sigmoid(alpha * (score - beta))} by class-balanced logistic regression, so that both pools weigh
     * the same regardless of their sizes. Uses Newton's method on standardized scores with a tiny ridge on the slope, which keeps the
     * fit finite when the pools barely overlap.
     *
     * @return {@code {alpha, beta}}, or {@code null} if the pools cannot be separated
     */
    private static double[] fitLogistic(float[] positives, int positiveCount, float[] negatives, int negativeCount) {
        final double positiveWeight = 0.5 / positiveCount;
        final double negativeWeight = 0.5 / negativeCount;
        double mean = 0;
        for (int i = 0; i < positiveCount; i++) {
            mean += positives[i];
        }
        for (int i = 0; i < negativeCount; i++) {
            mean += negatives[i];
        }
        mean /= positiveCount + negativeCount;
        double variance = 0;
        for (int i = 0; i < positiveCount; i++) {
            variance += (positives[i] - mean) * (positives[i] - mean);
        }
        for (int i = 0; i < negativeCount; i++) {
            variance += (negatives[i] - mean) * (negatives[i] - mean);
        }
        final double std = Math.sqrt(variance / (positiveCount + negativeCount));
        if (std == 0) {
            return null;
        }

        final double ridge = 1e-6;
        double a = 0;
        double b = 0;
        for (int iteration = 0; iteration < 100; iteration++) {
            // gradient and Hessian of the weighted negative log-likelihood in (a, b), accumulated over both pools
            final double[] sums = new double[5]; // grad a, grad b, hess aa, hess ab, hess bb
            accumulate(sums, positives, positiveCount, positiveWeight, 1, mean, std, a, b);
            accumulate(sums, negatives, negativeCount, negativeWeight, 0, mean, std, a, b);
            final double gradA = sums[0] + ridge * a;
            final double gradB = sums[1];
            final double hessAA = sums[2] + ridge;
            final double hessAB = sums[3];
            final double hessBB = sums[4];
            final double det = hessAA * hessBB - hessAB * hessAB;
            if (det <= 0 || Double.isFinite(det) == false) {
                return null;
            }
            final double stepA = (hessBB * gradA - hessAB * gradB) / det;
            final double stepB = (hessAA * gradB - hessAB * gradA) / det;
            a -= stepA;
            b -= stepB;
            if (Math.max(Math.abs(stepA), Math.abs(stepB)) < 1e-10) {
                break;
            }
        }
        final double alpha = a / std;
        final double beta = mean - b * std / a;
        if (Double.isFinite(alpha) == false || alpha <= 0 || Double.isFinite(beta) == false) {
            return null;
        }
        return new double[] { alpha, beta };
    }

    /** Adds one pool's terms of the logistic gradient and Hessian to {@code sums}; see {@link #fitLogistic}. */
    private static void accumulate(
        double[] sums,
        float[] scores,
        int count,
        double weight,
        int label,
        double mean,
        double std,
        double a,
        double b
    ) {
        for (int i = 0; i < count; i++) {
            final double z = (scores[i] - mean) / std;
            final double p = 1.0 / (1.0 + Math.exp(-(a * z + b)));
            final double residual = weight * (p - label);
            final double curvature = weight * p * (1 - p);
            sums[0] += residual * z;
            sums[1] += residual;
            sums[2] += curvature * z * z;
            sums[3] += curvature * z;
            sums[4] += curvature;
        }
    }

    /**
     * Calibrates a semantic field's sparse vector scores like {@link #estimateVector}: the token vectors of sampled chunks are used as
     * pseudo-queries, each scoring its nearest {@link #VECTOR_NEIGHBORS} documents, excluding its own, with the nested max-over-chunks
     * scoring that searches use. The query scores each token as query weight times document weight, as the sparse vector query does
     * without pruning.
     *
     * @return the parameters, or {@code null} if no chunk on this shard could be scored
     */
    private BayesianScoreEstimator.Parameters estimateSparse(Engine.Searcher searcher, SparseVectorFieldType fieldType) throws IOException {
        final String field = fieldType.name();
        final MappingLookup lookup = mapperService.mappingLookup();
        final String nestedPath = lookup.nestedLookup().getNestedParent(field);
        final NestedObjectMapper chunks = lookup.nestedLookup().getNestedMappers().get(nestedPath);
        final IndexSettings indexSettings = mapperService.getIndexSettings();
        final BitSetProducer parentFilter = mapperService.parserContext()
            .bitSetProducer(Queries.newNonNestedFilter(indexSettings.getIndexVersionCreated()));
        final IndexReader reader = searcher.getIndexReader();
        final List<LeafReaderContext> leaves = reader.leaves();

        // Sample live chunk documents of the field's nested object, then read each chunk's full token vector
        final Weight chunkFilter = searcher.createWeight(searcher.rewrite(chunks.nestedTypeFilter()), ScoreMode.COMPLETE_NO_SCORES, 1f);
        final int[][] sampled = sampleDocs(leaves, leaf -> {
            final Scorer scorer = chunkFilter.scorer(leaf);
            return scorer == null ? null : scorer.iterator();
        }, SPARSE_SAMPLES, SPARSE_SAMPLE_SEED);
        if (sampled == null) {
            return null;
        }

        // A chunk has a few hundred tokens at most; the clause limit only guards the pseudo-query's boolean query
        final int maxTokens = IndexSearcher.getMaxClauseCount();
        final float[] scores = new float[SPARSE_SAMPLES * VECTOR_NEIGHBORS];
        int scoreCount = 0;
        for (int i = 0; i < leaves.size(); i++) {
            final int[] docs = sampled[i];
            if (docs.length == 0) {
                continue;
            }
            final LeafReaderContext leaf = leaves.get(i);
            final BitSet parents = parentFilter.getBitSet(leaf);
            final List<PriorityQueue<WeightedTerm>> vectors = collectTerms(
                leaf,
                field,
                docs,
                maxTokens,
                (term, freq) -> XFeatureField.decodeFeatureValue(freq)
            );
            for (int d = 0; d < docs.length; d++) {
                final PriorityQueue<WeightedTerm> tokens = vectors.get(d);
                if (tokens.isEmpty()) {
                    continue;
                }
                final int sourceParent = leaf.docBase + (parents == null ? docs[d] : parents.nextSetBit(docs[d]));
                final BooleanQuery.Builder builder = new BooleanQuery.Builder();
                for (WeightedTerm token : tokens) {
                    builder.add(
                        new BoostQuery(FeatureField.newLinearQuery(field, token.term().utf8ToString(), 1f), token.weight()),
                        BooleanClause.Occur.SHOULD
                    );
                }
                final Query query = new ESToParentBlockJoinQuery(
                    builder.build(),
                    parentFilter,
                    org.apache.lucene.search.join.ScoreMode.Max,
                    nestedPath
                );
                final TopDocs topDocs = searcher.search(query, VECTOR_NEIGHBORS + 1);
                int kept = 0;
                for (ScoreDoc hit : topDocs.scoreDocs) {
                    if (kept == VECTOR_NEIGHBORS) {
                        break;
                    }
                    if (hit.doc == sourceParent) {
                        continue;
                    }
                    scores[scoreCount++] = hit.score;
                    kept++;
                }
            }
        }
        return poolParameters(scores, scoreCount);
    }

    /**
     * Turns pooled neighbor scores into sigmoid parameters: {@code beta = median} and {@code alpha = 1 / std}. No base rate is estimated.
     *
     * @return the parameters, or {@code null} if there are no scores
     */
    private static BayesianScoreEstimator.Parameters poolParameters(float[] scores, int scoreCount) {
        if (scoreCount == 0) {
            return null;
        }

        final float[] pooled = Arrays.copyOf(scores, scoreCount);
        Arrays.sort(pooled);
        final float beta = pooled[pooled.length / 2];
        double mean = 0;
        for (float score : pooled) {
            mean += score;
        }
        mean /= pooled.length;
        double variance = 0;
        for (float score : pooled) {
            variance += (score - mean) * (score - mean);
        }
        final double std = Math.sqrt(variance / pooled.length);
        final float alpha = std > 0 ? (float) (1.0 / std) : 1.0f;
        return new BayesianScoreEstimator.Parameters(alpha, beta, 0f);
    }

    /** Returns the shard-level doc id of the parent of the given shard-level doc id, or the doc itself if it is a parent. */
    private static int parentOf(int doc, List<LeafReaderContext> leaves, BitSetProducer parentFilter) throws IOException {
        final LeafReaderContext leaf = leaves.get(ReaderUtil.subIndex(doc, leaves));
        final BitSet parents = parentFilter.getBitSet(leaf);
        final int leafDoc = doc - leaf.docBase;
        return leaf.docBase + (parents == null ? leafDoc : parents.nextSetBit(leafDoc));
    }

    private Set<String> fullTextFields(FieldInfos fieldInfos) {
        final Set<String> fields = new HashSet<>();
        if (mapperService == null) {
            return fields;
        }
        for (FieldInfo fieldInfo : fieldInfos) {
            if (fieldInfo.getIndexOptions().compareTo(IndexOptions.DOCS_AND_FREQS) >= 0 && fieldInfo.hasNorms()) {
                MappedFieldType fieldType = mapperService.fieldType(fieldInfo.name);
                if (fieldType instanceof TextFieldMapper.TextFieldType) {
                    fields.add(fieldInfo.name);
                }
            }
        }
        return fields;
    }

    private static Set<String> mergedSegmentNames(Engine.Searcher searcher) {
        final Set<String> names = new HashSet<>();
        for (LeafReaderContext leaf : searcher.getLeafContexts()) {
            SegmentReader segmentReader = Lucene.tryUnwrapSegmentReader(leaf.reader());
            if (segmentReader != null
                && IndexWriter.SOURCE_MERGE.equals(segmentReader.getSegmentInfo().info.getDiagnostics().get(IndexWriter.SOURCE))) {
                names.add(segmentReader.getSegmentName());
            }
        }
        return names;
    }
}
