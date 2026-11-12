/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.shard;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.search.BayesianScoreEstimator;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.util.BitSet;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MappingLookup;
import org.elasticsearch.index.mapper.TextFieldMapper;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper.DenseVectorFieldType;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.search.vectors.VectorData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Estimates Bayesian BM25 parameters for the full-text fields of a shard whenever a refresh makes a newly merged segment visible.
 * It also estimates sigmoid calibration parameters for the dense vector fields of semantic fields, so that their scores can be fused
 * with the calibrated BM25 scores. The estimate runs asynchronously, at most one at a time, against the shard-wide searcher so that it
 * sees the same term statistics as queries do.
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

    private final ShardId shardId;
    private final Supplier<Engine> engineSupplier;
    private final MapperService mapperService;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile BayesianBM25ShardParameters parameters;

    BayesianBM25ShardEstimator(ShardId shardId, Supplier<Engine> engineSupplier, MapperService mapperService, Executor executor) {
        this.shardId = shardId;
        this.engineSupplier = engineSupplier;
        this.mapperService = mapperService;
        this.executor = executor;
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
                    byField.put(field, BayesianScoreEstimator.estimate(searcher, field));
                } catch (IOException | RuntimeException e) {
                    logger.warn("[{}] failed to estimate Bayesian BM25 parameters for field [{}]", shardId, field, e);
                }
            }
            for (DenseVectorFieldType fieldType : semanticVectorFields()) {
                try {
                    BayesianScoreEstimator.Parameters vectorParameters = estimateVector(searcher, fieldType);
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
     * Returns the indexed float dense vector fields that hold the chunk embeddings of semantic fields, i.e. the nested dense vector
     * fields under an inference field.
     */
    private List<DenseVectorFieldType> semanticVectorFields() {
        final List<DenseVectorFieldType> fields = new ArrayList<>();
        if (mapperService == null) {
            return fields;
        }
        final MappingLookup lookup = mapperService.mappingLookup();
        for (String inferenceField : lookup.inferenceFields().keySet()) {
            for (String name : lookup.getMatchingFieldNames(inferenceField + ".*")) {
                if (lookup.getFieldType(name) instanceof DenseVectorFieldType fieldType
                    && fieldType.isSearchable()
                    && lookup.nestedLookup().getNestedParent(name) != null) {
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
