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
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.search.BayesianScoreEstimator;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.store.AlreadyClosedException;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.TextFieldMapper;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Estimates Bayesian BM25 parameters for the full-text fields of a shard whenever a refresh makes a newly merged segment visible.
 * The estimate runs asynchronously, at most one at a time, against the shard-wide searcher so that it sees the same term statistics as
 * queries do.
 */
class BayesianBM25ShardEstimator implements ReferenceManager.RefreshListener {

    private static final Logger logger = LogManager.getLogger(BayesianBM25ShardEstimator.class);

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
            parameters = new BayesianBM25ShardParameters(segmentNames, byField);
        }
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
