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
import org.elasticsearch.action.support.IndicesOptions;
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
 * Covers a missing remote index — both a concrete name and a wildcard that matches nothing — for every query type that performs remote
 * inference, across all request modes and both {@code skip_unavailable} values.
 */
public class MissingRemoteIndexCrossClusterSearchIT extends AbstractSemanticCrossClusterSearchTestCase {
    private static final String MISSING_INDEX_NAME = "missing-index";
    private static final String MISSING_INDEX_WILDCARD = MISSING_INDEX_NAME + "*";

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
     * Verifies behavior for every request mode (minimize_roundtrips on, minimize_roundtrips off, scroll) and every query type that
     * triggers remote inference, for both a missing concrete remote index and a wildcard that matches no remote index.
     */
    public void testMissingRemoteIndex() throws Exception {
        for (int i = 0; i < 20; i++) {
            final IndicesOptions indicesOptions = randomIndicesOptions();
            for (QueryCase queryCase : buildQueryCases()) {
                minimizeRoundTripsTrueTestCase(queryCase, indicesOptions);
                minimizeRoundTripsFalseTestCase(queryCase, indicesOptions);
                scrollTestCase(queryCase, indicesOptions);
            }
        }
    }

    private void minimizeRoundTripsTrueTestCase(QueryCase queryCase, IndicesOptions indicesOptions) throws Exception {
        assertMissingRemoteIndex(queryCase, indicesOptions, s -> s.setCcsMinimizeRoundtrips(true), LOCAL_CLUSTER);
        assertMissingRemoteIndexWildcard(queryCase, indicesOptions, s -> s.setCcsMinimizeRoundtrips(true), LOCAL_CLUSTER);
    }

    private void minimizeRoundTripsFalseTestCase(QueryCase queryCase, IndicesOptions indicesOptions) throws Exception {
        assertMissingRemoteIndex(queryCase, indicesOptions, s -> s.setCcsMinimizeRoundtrips(false), null);
        assertMissingRemoteIndexWildcard(queryCase, indicesOptions, s -> s.setCcsMinimizeRoundtrips(false), null);
    }

    private void scrollTestCase(QueryCase queryCase, IndicesOptions indicesOptions) throws Exception {
        // Scroll implicitly sets ccs_minimize_roundtrips to false — this exercises the same lookup path as minimize_roundtrips=false.
        assertMissingRemoteIndex(queryCase, indicesOptions, s -> s.scroll(TimeValue.timeValueMinutes(1)), null);
        assertMissingRemoteIndexWildcard(queryCase, indicesOptions, s -> s.scroll(TimeValue.timeValueMinutes(1)), null);
    }

    private void assertMissingRemoteIndex(
        QueryCase queryCase,
        IndicesOptions indicesOptions,
        Consumer<SearchRequest> modifier,
        String expectedLocalClusterAlias
    ) throws Exception {
        // The remote cluster resolves the missing concrete name:
        // - ignoreUnavailable == false → IndexNotFoundException
        // - ignoreUnavailable == true, allowNoIndices == false → empty result set → IndexNotFoundException
        // - ignoreUnavailable == true, allowNoIndices == true → zero shards, no error
        final boolean remoteFails = indicesOptions.ignoreUnavailable() == false || indicesOptions.allowNoIndices() == false;
        assertRemoteIndexExpression(queryCase, indicesOptions, modifier, expectedLocalClusterAlias, MISSING_INDEX_NAME, remoteFails);
    }

    private void assertMissingRemoteIndexWildcard(
        QueryCase queryCase,
        IndicesOptions indicesOptions,
        Consumer<SearchRequest> modifier,
        String expectedLocalClusterAlias
    ) throws Exception {
        // The remote cluster resolves the non-matching wildcard. The outcome depends on wildcard expansion:
        // - expandWildcardExpressions == true: fails only when allowNoIndices == false (ignoreUnavailable is irrelevant)
        // - expandWildcardExpressions == false: the wildcard is treated as a concrete name, so the concrete-name rule applies
        // (ignoreUnavailable == false || allowNoIndices == false)
        final boolean remoteFails = indicesOptions.expandWildcardExpressions()
            ? indicesOptions.allowNoIndices() == false
            : indicesOptions.ignoreUnavailable() == false || indicesOptions.allowNoIndices() == false;
        assertRemoteIndexExpression(queryCase, indicesOptions, modifier, expectedLocalClusterAlias, MISSING_INDEX_WILDCARD, remoteFails);
    }

    private void assertRemoteIndexExpression(
        QueryCase queryCase,
        IndicesOptions indicesOptions,
        Consumer<SearchRequest> modifier,
        String expectedLocalClusterAlias,
        String remoteIndexExpression,
        boolean remoteFails
    ) throws Exception {
        final List<String> indices = List.of(LOCAL_INDEX_NAME, fullyQualifiedIndexName(REMOTE_CLUSTER, remoteIndexExpression));
        final Consumer<SearchRequest> modifierWithOptions = modifier.andThen(s -> s.indicesOptions(indicesOptions));
        final String missingIndexError = missingIndexError(remoteIndexExpression);
        final SetOnce<String> scrollId = new SetOnce<>();
        try {
            if (remoteFails == false) {
                // The remote cluster silently contributes zero shards — both clusters report SUCCESSFUL.
                assertSearchResponse(
                    queryCase.query(),
                    indices,
                    List.of(new SearchResult(expectedLocalClusterAlias, LOCAL_INDEX_NAME, queryCase.expectedDocId())),
                    null,
                    modifierWithOptions,
                    r -> scrollId.set(r.getScrollId())
                );
            } else if (skipUnavailable) {
                assertSearchResponse(
                    queryCase.query(),
                    indices,
                    List.of(new SearchResult(expectedLocalClusterAlias, LOCAL_INDEX_NAME, queryCase.expectedDocId())),
                    new ClusterFailure(
                        SearchResponse.Cluster.Status.SKIPPED,
                        Set.of(new FailureCause(IndexNotFoundException.class, missingIndexError))
                    ),
                    modifierWithOptions,
                    r -> scrollId.set(r.getScrollId())
                );
            } else {
                assertSearchFailure(queryCase.query(), indices, IndexNotFoundException.class, missingIndexError, modifierWithOptions);
            }
        } finally {
            if (scrollId.get() != null) {
                client().prepareClearScroll().addScrollId(scrollId.get()).get(TEST_REQUEST_TIMEOUT);
            }
        }
    }

    private static IndicesOptions randomIndicesOptions() {
        return IndicesOptions.fromOptions(
            randomBoolean(), // ignoreUnavailable
            randomBoolean(), // allowNoIndices
            randomBoolean(), // expandToOpenIndices
            randomBoolean(), // expandToClosedIndices
            randomBoolean(), // expandToHiddenIndices
            randomBoolean(), // allowAliasesToMultipleIndices
            randomBoolean(), // forbidClosedIndices
            randomBoolean(), // ignoreAliases
            randomBoolean()  // ignoreThrottled
        );
    }

    private static List<QueryCase> buildQueryCases() {
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

    private static String missingIndexError(String expression) {
        return "no such index [" + expression + "]";
    }

    private static String getDocId(String field) {
        return field + "_doc";
    }

    private record QueryCase(QueryBuilder query, String expectedDocId) {}
}
