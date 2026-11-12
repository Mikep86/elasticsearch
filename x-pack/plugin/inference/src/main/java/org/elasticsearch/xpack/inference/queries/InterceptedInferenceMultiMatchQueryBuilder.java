/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.queries;

import org.apache.lucene.search.BayesianScoreEstimator;
import org.apache.lucene.search.BayesianScoreQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.DisMaxQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.QueryShardException;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.shard.BayesianBM25ShardParameters;
import org.elasticsearch.inference.InferenceResults;
import org.elasticsearch.inference.InferenceStringGroup;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.search.internal.MaxClauseCountQueryVisitor;
import org.elasticsearch.xpack.core.ml.inference.results.TextExpansionResults;
import org.elasticsearch.xpack.inference.mapper.SemanticFieldMapper;
import org.elasticsearch.xpack.inference.mapper.SemanticTextField;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Intercepts {@code multi_match} queries so that {@code semantic_text} fields are queried with a {@link SemanticQueryBuilder}.
 * Only the {@code best_fields}, {@code most_fields} and {@code bayesian} types can be combined with semantic queries.
 */
public class InterceptedInferenceMultiMatchQueryBuilder extends InterceptedInferenceQueryBuilder<MultiMatchQueryBuilder> {
    public static final String NAME = "intercepted_inference_multi_match";

    // Intentionally shares the transport version added for the bayesian multi_match type: only one new transport version is supported per
    // branch, and both changes ship together.
    private static final TransportVersion MULTI_MATCH_QUERY_INTERCEPTION = TransportVersion.fromName("multi_match_bayesian_type");

    /**
     * The semantic clauses of a [bayesian] query by field, which are fused with the lexical fields in {@link #doToQuery}. Only set while
     * rewriting on a data node, so it is never serialized.
     */
    @Nullable
    private final Map<String, QueryBuilder> semanticQueries;

    public InterceptedInferenceMultiMatchQueryBuilder(MultiMatchQueryBuilder originalQuery) {
        super(originalQuery);
        this.semanticQueries = null;
    }

    public InterceptedInferenceMultiMatchQueryBuilder(
        MultiMatchQueryBuilder originalQuery,
        Map<FullyQualifiedInferenceId, InferenceResults> inferenceResultsMap
    ) {
        super(originalQuery, inferenceResultsMap);
        this.semanticQueries = null;
    }

    public InterceptedInferenceMultiMatchQueryBuilder(StreamInput in) throws IOException {
        super(in);
        this.semanticQueries = null;
    }

    private InterceptedInferenceMultiMatchQueryBuilder(
        InterceptedInferenceMultiMatchQueryBuilder other,
        Map<FullyQualifiedInferenceId, InferenceResults> inferenceResultsMap,
        PlainActionFuture<InferenceQueryUtils.InferenceInfo> inferenceInfoFuture,
        boolean interceptedCcsRequest
    ) {
        super(other, inferenceResultsMap, inferenceInfoFuture, interceptedCcsRequest);
        this.semanticQueries = null;
    }

    private InterceptedInferenceMultiMatchQueryBuilder(
        InterceptedInferenceMultiMatchQueryBuilder other,
        Map<String, QueryBuilder> semanticQueries
    ) {
        super(other, other.inferenceResultsMap, other.inferenceInfoFuture, other.interceptedCcsRequest);
        this.semanticQueries = semanticQueries;
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
            if (originalQuery.type() == MultiMatchQueryBuilder.Type.BAYESIAN
                && inferenceInfo.inferenceResultsMap().values().stream().anyMatch(TextExpansionResults.class::isInstance)) {
                throw unsupportedSparseException();
            }
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

        if (originalQuery.type() == MultiMatchQueryBuilder.Type.BAYESIAN) {
            validateNoSparseFields(inferenceFields.keySet(), indexMetadataContext);
            // The semantic clauses are fused with the lexical fields in doToQuery, once doRewriteIntercepted has rewritten them
            return this;
        }

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
    protected QueryBuilder doRewriteIntercepted(QueryRewriteContext queryRewriteContext) throws IOException {
        if (originalQuery.type() != MultiMatchQueryBuilder.Type.BAYESIAN || queryRewriteContext.convertToSearchExecutionContext() == null) {
            return this;
        }

        // Rewrite the semantic clauses down to nested kNN queries, one rewrite round per call, until they stop changing
        String queryText = originalQuery.value().toString();
        Map<String, QueryBuilder> rewrittenQueries = new LinkedHashMap<>();
        boolean changed = semanticQueries == null;
        for (String field : getQueryFields(queryRewriteContext).inferenceFields().keySet()) {
            QueryBuilder clause = semanticQueries == null
                ? new SemanticQueryBuilder(field, queryText, null, inferenceResultsMap)
                : semanticQueries.get(field);
            QueryBuilder rewrittenClause = clause.rewrite(queryRewriteContext);
            changed |= rewrittenClause != clause;
            rewrittenQueries.put(field, rewrittenClause);
        }
        if (changed == false) {
            return this;
        }
        return new InterceptedInferenceMultiMatchQueryBuilder(this, rewrittenQueries).boost(originalQuery.boost())
            .queryName(originalQuery.queryName());
    }

    @Override
    public Query doToQuery(SearchExecutionContext context, MaxClauseCountQueryVisitor queryVisitor) throws IOException {
        if (semanticQueries == null) {
            // TODO: Should semanticQueries ever be null here?
            return super.doToQuery(context, queryVisitor);
        }

        Map<String, Float> inferenceFields = getQueryFields(context).inferenceFields();
        BayesianBM25ShardParameters shardParameters = context.getBayesianBM25Parameters();
        Map<Query, Float> semanticClauses = new LinkedHashMap<>();
        for (Map.Entry<String, QueryBuilder> entry : semanticQueries.entrySet()) {
            String field = entry.getKey();
            BayesianScoreEstimator.Parameters parameters = shardParameters == null
                ? null
                : shardParameters.get(SemanticTextField.getEmbeddingsFieldName(field));

            if (parameters == null) {
                throw new QueryShardException(
                    context,
                    "[multi_match] type [bayesian] has no vector calibration parameters for field ["
                        + field
                        + "] on this shard; they are estimated for float dense embeddings, after the first merge"
                );
            }

            Query query = entry.getValue().toQuery(context, queryVisitor);
            semanticClauses.put(new BayesianScoreQuery(query, parameters.alpha(), parameters.beta()), inferenceFields.get(field));
        }
        return originalQuery.toBayesianQuery(context, queryVisitor, semanticQueries.keySet(), semanticClauses);
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        if (semanticQueries != null) {
            throw new IllegalStateException("semanticQueries should never be serialized");
        }

        super.doWriteTo(out);
    }

    @Override
    protected boolean doEquals(InterceptedInferenceQueryBuilder<MultiMatchQueryBuilder> other) {
        return super.doEquals(other)
            && Objects.equals(semanticQueries, ((InterceptedInferenceMultiMatchQueryBuilder) other).semanticQueries);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(super.doHashCode(), semanticQueries);
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
            case CROSS_FIELDS, PHRASE, PHRASE_PREFIX, BOOL_PREFIX -> throw unsupportedTypeException();
            case BAYESIAN -> throw new IllegalStateException("[bayesian] queries are fused in doToQuery");
        };
    }

    /**
     * Throws if the query's type can't be combined with semantic queries.
     */
    private void validateTypeSupportsInferenceFields() {
        switch (originalQuery.type()) {
            case BEST_FIELDS, MOST_FIELDS, BAYESIAN -> {
            }
            case CROSS_FIELDS, PHRASE, PHRASE_PREFIX, BOOL_PREFIX -> throw unsupportedTypeException();
        }
    }

    /**
     * Throws if any of the fields is backed by a sparse embedding endpoint: sparse scores are unbounded, but log-odds fusion needs
     * probabilities, and there is no calibration for them yet.
     */
    private static void validateNoSparseFields(Iterable<String> inferenceFields, QueryRewriteContext indexMetadataContext) {
        for (String field : inferenceFields) {
            if (indexMetadataContext.getFieldType(field) instanceof SemanticFieldMapper.SemanticFieldType semanticFieldType
                && semanticFieldType.getModelSettings() != null
                && semanticFieldType.getModelSettings().taskType() == TaskType.SPARSE_EMBEDDING) {
                throw unsupportedSparseException();
            }
        }
    }

    private static IllegalArgumentException unsupportedSparseException() {
        return new IllegalArgumentException(
            "[" + MultiMatchQueryBuilder.NAME + "] query type [bayesian] does not support sparse embedding semantic_text fields"
        );
    }

    private IllegalArgumentException unsupportedTypeException() {
        return new IllegalArgumentException(
            "["
                + MultiMatchQueryBuilder.NAME
                + "] query type ["
                + originalQuery.type().parseField().getPreferredName()
                + "] is not supported when querying semantic_text fields. Supported types: [best_fields, most_fields, bayesian]"
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
