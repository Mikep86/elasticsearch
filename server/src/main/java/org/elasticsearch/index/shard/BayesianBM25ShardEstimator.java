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
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
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
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.store.AlreadyClosedException;
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
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.search.vectors.VectorData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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

/**
 * Estimates Bayesian BM25 parameters for the full-text fields of a shard whenever a refresh makes a newly merged segment visible.
 * Text fields are calibrated with document-derived pseudo-queries: each sampled document is scored against its own top terms, which
 * gives a realistic strong-match score that anchors the sigmoid. No base rate is estimated.
 * It also estimates sigmoid calibration parameters for the dense and sparse vector fields of semantic fields, so that their scores can be
 * fused with the calibrated BM25 scores; sparse fields are calibrated like dense ones, with sampled chunks' token vectors as pseudo-queries.
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

    /** Number of chunks whose token vectors are sampled as pseudo-queries when calibrating a sparse vector field. */
    private static final int SPARSE_SAMPLES = 200;
    private static final long SPARSE_SAMPLE_SEED = 42;

    /** Number of documents sampled as pseudo-query sources when calibrating a text field. */
    private static final int TEXT_DOC_SAMPLES = 200;
    private static final long TEXT_SAMPLE_SEED = 42;
    /** Saturation used to rank a document's terms; the document's length is the same for all of its terms, so it is left out. */
    private static final float TERM_RANK_K1 = 1.2f;
    /** Probabilities that a zero score and the median strong-match score map to. */
    private static final double P_LO = 0.05;
    private static final double P_HI = 0.95;

    private final ShardId shardId;
    private final Supplier<Engine> engineSupplier;
    private final MapperService mapperService;
    private final Executor executor;
    private final int pseudoQueryTerms;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile BayesianBM25ShardParameters parameters;

    BayesianBM25ShardEstimator(
        ShardId shardId,
        Supplier<Engine> engineSupplier,
        MapperService mapperService,
        Executor executor,
        int pseudoQueryTerms
    ) {
        this.shardId = shardId;
        this.engineSupplier = engineSupplier;
        this.mapperService = mapperService;
        this.executor = executor;
        this.pseudoQueryTerms = pseudoQueryTerms;
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
            for (String field : fullTextFields(FieldInfos.getMergedFieldInfos(searcher.getIndexReader()))) {
                try {
                    BayesianScoreEstimator.Parameters textParameters = estimateText(searcher, field);
                    if (textParameters != null) {
                        byField.put(field, textParameters);
                    } else {
                        logger.debug("[{}] no documents to estimate Bayesian BM25 parameters for field [{}]", shardId, field);
                    }
                } catch (IOException | RuntimeException e) {
                    logger.warn("[{}] failed to estimate Bayesian BM25 parameters for field [{}]", shardId, field, e);
                }
            }
            for (MappedFieldType fieldType : semanticEmbeddingFields()) {
                try {
                    BayesianScoreEstimator.Parameters vectorParameters = switch (fieldType) {
                        case DenseVectorFieldType dense -> estimateVector(searcher, dense);
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
            parameters = new BayesianBM25ShardParameters(segmentNames, byField);
        }
    }

    /**
     * Calibrates a text field with document-derived pseudo-queries: each sampled document is scored against a query made of its own
     * top {@link #pseudoQueryTerms} terms. The median of those scores is the strong-match score {@code max}, and the range
     * {@code [0, max]} is mapped to the probabilities {@code [P_LO, P_HI]}. No base rate is estimated.
     *
     * @return the parameters, or {@code null} if no document on this shard could be scored
     */
    private BayesianScoreEstimator.Parameters estimateText(Engine.Searcher searcher, String field) throws IOException {
        final IndexReader reader = searcher.getIndexReader();
        final List<LeafReaderContext> leaves = reader.leaves();

        // Sample live documents that have the field, i.e. have norms, then score each one against its own top terms
        final int[][] sampled = sampleDocs(leaves, leaf -> leaf.reader().getNormValues(field), TEXT_DOC_SAMPLES, TEXT_SAMPLE_SEED);
        if (sampled == null) {
            return null;
        }
        final TermWeigher weigher = new IdfWeigher(reader, field);
        final float[] scores = new float[TEXT_DOC_SAMPLES];
        int scoreCount = 0;
        for (int i = 0; i < leaves.size(); i++) {
            final int[] docs = sampled[i];
            if (docs.length == 0) {
                continue;
            }
            final LeafReaderContext leaf = leaves.get(i);
            final List<PriorityQueue<WeightedTerm>> topTerms = collectTerms(leaf, field, docs, pseudoQueryTerms, weigher);
            for (int d = 0; d < docs.length; d++) {
                final float score = scoreOwnTerms(searcher, leaf, field, docs[d], topTerms.get(d));
                if (score > 0) {
                    scores[scoreCount++] = score;
                }
            }
        }
        if (scoreCount == 0) {
            return null;
        }

        final float[] pooled = Arrays.copyOf(scores, scoreCount);
        Arrays.sort(pooled);
        final float max = pooled[pooled.length / 2];
        final float alpha = (float) ((logit(P_HI) - logit(P_LO)) / max);
        final float beta = (float) (max - logit(P_HI) / alpha);
        return new BayesianScoreEstimator.Parameters(alpha, beta, 0f);
    }

    private static double logit(double p) {
        return Math.log(p / (1 - p));
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
                final long docFreq = reader.docFreq(new Term(field, term));
                lastIdf = (float) Math.log(1 + (docCount - docFreq + 0.5) / (docFreq + 0.5));
                lastTerm = BytesRef.deepCopyOf(term);
            }
            return lastIdf * freq / (freq + TERM_RANK_K1);
        }
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

    /** Returns the score the document gets for a disjunction of the given terms, using the field's similarity, or 0 if none match. */
    private static float scoreOwnTerms(
        Engine.Searcher searcher,
        LeafReaderContext leaf,
        String field,
        int doc,
        PriorityQueue<WeightedTerm> terms
    ) throws IOException {
        if (terms.isEmpty()) {
            return 0f;
        }
        final BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (WeightedTerm term : terms) {
            builder.add(new TermQuery(new Term(field, term.term())), BooleanClause.Occur.SHOULD);
        }
        final Weight weight = searcher.createWeight(searcher.rewrite(builder.build()), ScoreMode.COMPLETE, 1f);
        final Scorer scorer = weight.scorer(leaf);
        if (scorer == null || scorer.iterator().advance(doc) != doc) {
            return 0f;
        }
        return scorer.score();
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
     * Calibrates a semantic field's vector scores by using indexed vectors as pseudo-queries: each sampled vector retrieves its nearest
     * {@link #VECTOR_NEIGHBORS} documents, excluding its own, with the same nested kNN query that searches use. The pooled scores give
     * {@code beta = median} and {@code alpha = 1 / std}, as {@link BayesianScoreEstimator} does for BM25. No base rate is estimated.
     *
     * @return the parameters, or {@code null} if the field has no vectors on this shard
     */
    private BayesianScoreEstimator.Parameters estimateVector(Engine.Searcher searcher, DenseVectorFieldType fieldType) throws IOException {
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
        int scoreCount = 0;
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
                if (parentOf(hit.doc, leaves, parentFilter) == sourceParents[i]) {
                    continue;
                }
                scores[scoreCount++] = hit.score;
                kept++;
            }
        }
        return poolParameters(scores, scoreCount);
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
