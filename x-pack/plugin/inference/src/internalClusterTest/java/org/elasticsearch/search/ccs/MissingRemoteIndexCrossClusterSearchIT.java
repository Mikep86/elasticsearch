/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.search.ccs;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.inference.SimilarityMeasure;
import org.elasticsearch.search.vectors.KnnVectorQueryBuilder;
import org.elasticsearch.xpack.core.ml.search.SparseVectorQueryBuilder;
import org.elasticsearch.xpack.core.ml.vectors.TextEmbeddingQueryVectorBuilder;
import org.elasticsearch.xpack.inference.queries.SemanticQueryBuilder;
import org.junit.Before;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Covers a missing remote index for every query type that performs remote inference, across all request modes and both
 * {@code skip_unavailable} values.
 */
public class MissingRemoteIndexCrossClusterSearchIT extends AbstractSemanticCrossClusterSearchTestCase {
    private static final String MISSING_INDEX_NAME = "missing-index";
    private static final String MISSING_INDEX_ERROR = "no such index [" + MISSING_INDEX_NAME + "]";

    private static final String SPARSE_INFERENCE_ID = "sparse-inference-id";
    private static final String DENSE_INFERENCE_ID = "dense-inference-id";

    private static final String SPARSE_FIELD = "sparse-field";
    private static final String DENSE_FIELD = "dense-field";

    private static final String FIELD_VALUE = "value";

    private final boolean skipUnavailable;

    public MissingRemoteIndexCrossClusterSearchIT(@Name("skipUnavailable") boolean skipUnavailable) {
        this.skipUnavailable = skipUnavailable;
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() {
        return List.of(new Object[] { true }, new Object[] { false });
    }

    @Override
    protected Map<String, Boolean> skipUnavailableForRemoteClusters() {
        return Map.of(REMOTE_CLUSTER, skipUnavailable);
    }

    @Before
    public void setupClusters() throws Exception {
        final TestIndexInfo localIndexInfo = new TestIndexInfo(
            LOCAL_INDEX_NAME,
            Map.of(
                SPARSE_INFERENCE_ID,
                sparseEmbeddingServiceSettings(),
                DENSE_INFERENCE_ID,
                embeddingServiceSettings(256, SimilarityMeasure.COSINE, DenseVectorFieldMapper.ElementType.FLOAT)
            ),
            Map.of(SPARSE_FIELD, semanticTextMapping(SPARSE_INFERENCE_ID), DENSE_FIELD, semanticTextMapping(DENSE_INFERENCE_ID)),
            Map.of(getDocId(SPARSE_FIELD), Map.of(SPARSE_FIELD, FIELD_VALUE), getDocId(DENSE_FIELD), Map.of(DENSE_FIELD, FIELD_VALUE))
        );
        setupCluster(LOCAL_CLUSTER, localIndexInfo);
        waitUntilRemoteClusterConnected(REMOTE_CLUSTER);
    }

    /**
     * Verifies that a missing remote index is tolerated when {@code skip_unavailable} is true, and rejected when it is false, for every
     * request mode (minimize_roundtrips on, minimize_roundtrips off, scroll) and every query type that triggers remote inference.
     */
    public void testMissingRemoteIndex() throws Exception {
        for (QueryCase queryCase : buildQueryCases()) {
            minimizeRoundTripsTrueTestCase(queryCase);
            minimizeRoundTripsFalseTestCase(queryCase);
            scrollTestCase(queryCase);
        }
    }

    private void minimizeRoundTripsTrueTestCase(QueryCase queryCase) throws Exception {
        assertMissingRemoteIndex(queryCase, s -> s.setCcsMinimizeRoundtrips(true), LOCAL_CLUSTER);
    }

    private void minimizeRoundTripsFalseTestCase(QueryCase queryCase) throws Exception {
        assertMissingRemoteIndex(queryCase, s -> s.setCcsMinimizeRoundtrips(false), null);
    }

    private void scrollTestCase(QueryCase queryCase) throws Exception {
        // Scroll implicitly sets ccs_minimize_roundtrips to false — this exercises the same lookup path as minimize_roundtrips=false.
        assertMissingRemoteIndex(queryCase, s -> s.scroll(TimeValue.timeValueMinutes(1)), null);
    }

    private void assertMissingRemoteIndex(QueryCase queryCase, Consumer<SearchRequest> modifier, String expectedLocalClusterAlias)
        throws Exception {
        final List<String> indices = List.of(LOCAL_INDEX_NAME, fullyQualifiedIndexName(REMOTE_CLUSTER, MISSING_INDEX_NAME));

        final SetOnce<String> scrollId = new SetOnce<>();
        try {
            if (skipUnavailable) {
                assertSearchResponse(
                    queryCase.query(),
                    indices,
                    List.of(new SearchResult(expectedLocalClusterAlias, LOCAL_INDEX_NAME, queryCase.expectedDocId())),
                    new ClusterFailure(
                        SearchResponse.Cluster.Status.SKIPPED,
                        Set.of(new FailureCause(IndexNotFoundException.class, MISSING_INDEX_ERROR))
                    ),
                    modifier,
                    r -> scrollId.set(r.getScrollId())
                );
            } else {
                assertSearchFailure(queryCase.query(), indices, IndexNotFoundException.class, MISSING_INDEX_ERROR, modifier);
            }
        } finally {
            if (scrollId.get() != null) {
                client().prepareClearScroll().addScrollId(scrollId.get()).get(TEST_REQUEST_TIMEOUT);
            }
        }
    }

    private List<QueryCase> buildQueryCases() {
        return List.of(
            new QueryCase(
                new KnnVectorQueryBuilder(
                    DENSE_FIELD,
                    new TextEmbeddingQueryVectorBuilder(null, randomAlphaOfLength(10)),
                    10,
                    100,
                    10f,
                    null
                ),
                getDocId(DENSE_FIELD)
            ),
            new QueryCase(new MatchQueryBuilder(SPARSE_FIELD, FIELD_VALUE), getDocId(SPARSE_FIELD)),
            new QueryCase(new SparseVectorQueryBuilder(SPARSE_FIELD, null, FIELD_VALUE), getDocId(SPARSE_FIELD)),
            new QueryCase(new SemanticQueryBuilder(SPARSE_FIELD, FIELD_VALUE), getDocId(SPARSE_FIELD))
        );
    }

    private static String getDocId(String field) {
        return field + "_doc";
    }

    private record QueryCase(QueryBuilder query, String expectedDocId) {}
}
