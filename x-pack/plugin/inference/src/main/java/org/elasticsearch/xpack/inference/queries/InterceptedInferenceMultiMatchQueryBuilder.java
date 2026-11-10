/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.queries;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.DisMaxQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.inference.InferenceResults;
import org.elasticsearch.inference.InferenceStringGroup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Intercepts {@code multi_match} queries so that {@code semantic_text} fields are queried with a {@link SemanticQueryBuilder}.
 * Only the {@code best_fields} and {@code most_fields} types can be combined with semantic queries.
 */
public class InterceptedInferenceMultiMatchQueryBuilder extends InterceptedInferenceQueryBuilder<MultiMatchQueryBuilder> {
    public static final String NAME = "intercepted_inference_multi_match";

    // Intentionally shares the transport version added for the bayesian multi_match type: only one new transport version is supported per
    // branch, and both changes ship together.
    private static final TransportVersion MULTI_MATCH_QUERY_INTERCEPTION = TransportVersion.fromName("multi_match_bayesian_type");

    public InterceptedInferenceMultiMatchQueryBuilder(MultiMatchQueryBuilder originalQuery) {
        super(originalQuery);
    }

    public InterceptedInferenceMultiMatchQueryBuilder(
        MultiMatchQueryBuilder originalQuery,
        Map<FullyQualifiedInferenceId, InferenceResults> inferenceResultsMap
    ) {
        super(originalQuery, inferenceResultsMap);
    }

    public InterceptedInferenceMultiMatchQueryBuilder(StreamInput in) throws IOException {
        super(in);
    }

    private InterceptedInferenceMultiMatchQueryBuilder(
        InterceptedInferenceMultiMatchQueryBuilder other,
        Map<FullyQualifiedInferenceId, InferenceResults> inferenceResultsMap,
        PlainActionFuture<InferenceQueryUtils.InferenceInfo> inferenceInfoFuture,
        boolean interceptedCcsRequest
    ) {
        super(other, inferenceResultsMap, inferenceInfoFuture, interceptedCcsRequest);
    }

    @Override
    protected Map<String, Float> getFields() {
        return originalQuery.fields();
    }

    @Override
    protected InferenceStringGroup getInput() {
        return new InferenceStringGroup(originalQuery.value().toString());
    }

    @Override
    protected QueryBuilder doRewriteBwC(QueryRewriteContext queryRewriteContext) {
        // Nodes that don't know this query type can't deserialize it, so fall back to the original query
        if (queryRewriteContext.getMinTransportVersion().supports(MULTI_MATCH_QUERY_INTERCEPTION) == false) {
            return originalQuery;
        }
        return this;
    }

    @Override
    protected void postInferenceCoordinatorNodeValidate(InferenceQueryUtils.InferenceInfo inferenceInfo) {
        if (inferenceInfo.inferenceFieldCount() > 0) {
            validateTypeSupportsInferenceFields();
        }
    }

    @Override
    protected InterceptedInferenceQueryBuilder<MultiMatchQueryBuilder> copy(
        Map<FullyQualifiedInferenceId, InferenceResults> inferenceResultsMap,
        PlainActionFuture<InferenceQueryUtils.InferenceInfo> inferenceInfoFuture,
        boolean interceptedCcsRequest
    ) {
        return new InterceptedInferenceMultiMatchQueryBuilder(this, inferenceResultsMap, inferenceInfoFuture, interceptedCcsRequest);
    }

    @Override
    protected QueryBuilder queryFields(
        Map<String, Float> inferenceFields,
        Map<String, Float> nonInferenceFields,
        QueryRewriteContext indexMetadataContext
    ) {
        if (inferenceFields.isEmpty()) {
            return originalQuery;
        }
        // Data node safety net: a wildcard can resolve to inference fields only in some indices
        validateTypeSupportsInferenceFields();

        List<QueryBuilder> clauses = new ArrayList<>();
        String queryText = originalQuery.value().toString();
        for (Map.Entry<String, Float> entry : inferenceFields.entrySet()) {
            clauses.add(new SemanticQueryBuilder(entry.getKey(), queryText, null, inferenceResultsMap).boost(entry.getValue()));
        }
        if (nonInferenceFields.isEmpty() == false) {
            clauses.add(lexicalQuery(nonInferenceFields));
        }

        QueryBuilder rewritten;
        if (clauses.size() == 1) {
            rewritten = clauses.getFirst();
        } else {
            rewritten = combine(clauses);
        }
        return rewritten.boost(originalQuery.boost()).queryName(originalQuery.queryName());
    }

    @Override
    protected boolean resolveWildcards() {
        return true;
    }

    @Override
    protected boolean useDefaultFields() {
        return true;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    private QueryBuilder combine(List<QueryBuilder> clauses) {
        return switch (originalQuery.type()) {
            case BEST_FIELDS -> {
                DisMaxQueryBuilder disMax = new DisMaxQueryBuilder();
                clauses.forEach(disMax::add);
                if (originalQuery.tieBreaker() != null) {
                    disMax.tieBreaker(originalQuery.tieBreaker());
                } else {
                    disMax.tieBreaker(MultiMatchQueryBuilder.Type.BEST_FIELDS.tieBreaker());
                }
                yield disMax;
            }
            case MOST_FIELDS -> {
                BoolQueryBuilder bool = new BoolQueryBuilder();
                clauses.forEach(bool::should);
                yield bool;
            }
            case CROSS_FIELDS, PHRASE, PHRASE_PREFIX, BOOL_PREFIX, BAYESIAN -> throw unsupportedTypeException();
        };
    }

    /**
     * Throws if the query's type can't be combined with semantic queries.
     */
    private void validateTypeSupportsInferenceFields() {
        switch (originalQuery.type()) {
            case BEST_FIELDS, MOST_FIELDS -> {
            }
            // TODO: BAYESIAN could fuse semantic scores by log-odds once vector scores can be calibrated
            case CROSS_FIELDS, PHRASE, PHRASE_PREFIX, BOOL_PREFIX, BAYESIAN -> throw unsupportedTypeException();
        }
    }

    private IllegalArgumentException unsupportedTypeException() {
        return new IllegalArgumentException(
            "["
                + MultiMatchQueryBuilder.NAME
                + "] query type ["
                + originalQuery.type().parseField().getPreferredName()
                + "] is not supported when querying semantic_text fields. Supported types: [best_fields, most_fields]"
        );
    }

    /**
     * The original query restricted to the given fields, with every option except boost and query name preserved.
     */
    private MultiMatchQueryBuilder lexicalQuery(Map<String, Float> fields) {
        MultiMatchQueryBuilder lexical = new MultiMatchQueryBuilder(originalQuery.value()).fields(fields)
            .type(originalQuery.type())
            .operator(originalQuery.operator())
            .analyzer(originalQuery.analyzer())
            .slop(originalQuery.slop())
            .prefixLength(originalQuery.prefixLength())
            .maxExpansions(originalQuery.maxExpansions())
            .minimumShouldMatch(originalQuery.minimumShouldMatch())
            .fuzzyRewrite(originalQuery.fuzzyRewrite())
            .tieBreaker(originalQuery.tieBreaker())
            .lenient(originalQuery.lenient())
            .zeroTermsQuery(originalQuery.zeroTermsQuery())
            .autoGenerateSynonymsPhraseQuery(originalQuery.autoGenerateSynonymsPhraseQuery())
            .fuzzyTranspositions(originalQuery.fuzzyTranspositions());

        // fuzziness is null unless set explicitly, and its setter rejects null
        if (originalQuery.fuzziness() != null) {
            lexical.fuzziness(originalQuery.fuzziness());
        }
        return lexical;
    }
}
