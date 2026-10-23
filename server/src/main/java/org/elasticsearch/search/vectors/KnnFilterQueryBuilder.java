/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.vectors;

import org.apache.lucene.search.Query;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.index.mapper.NestedObjectMapper;
import org.elasticsearch.index.query.LeafQueryBuilder;
import org.elasticsearch.index.query.MatchNoneQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.search.NestedHelper;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Objects;

/**
 * A filter of a knn query on {@code vectorField}, joined to the vector's nested level like the knn query joins its filters, so
 * that the exact queries inner hits rewrite knn queries to select the same documents. Built on the data node, never serialized.
 * A leaf query, so the join hides the filter's clauses from visitors and inner hits are not extracted from the filter.
 * <p>
 * With {@code rootScoped} the filter is built at the root, like the filters of a top level knn search, otherwise in the current
 * nested scope, like the filters of a knn query.
 */
final class KnnFilterQueryBuilder extends LeafQueryBuilder<KnnFilterQueryBuilder> {

    static final String NAME = "knn_filter";

    private final String vectorField;
    private final QueryBuilder filter;
    private final boolean rootScoped;

    KnnFilterQueryBuilder(String vectorField, QueryBuilder filter, boolean rootScoped) {
        this.vectorField = Objects.requireNonNull(vectorField);
        this.filter = Objects.requireNonNull(filter);
        this.rootScoped = rootScoped;
    }

    @Override
    protected Query doToQuery(SearchExecutionContext context) throws IOException {
        // a top level knn search builds its filters at the root, a knn query in the current nested scope (a no-op unwind)
        final Query query;
        NestedObjectMapper scope = rootScoped ? null : context.nestedScope().getObjectMapper();
        try (Releasable ignored = context.nestedScope().unwindTo(scope)) {
            query = filter.toQuery(context);
        }

        String vectorPath = context.nestedLookup().getNestedParent(vectorField);
        if (vectorPath == null) {
            return query;
        }

        NestedObjectMapper parentLevel = context.nestedScope().parentLevel();
        return NestedHelper.joinToNestedLevel(query, vectorPath, parentLevel == null ? null : parentLevel.fullPath(), context);
    }

    @Override
    protected QueryBuilder doRewrite(QueryRewriteContext queryRewriteContext) throws IOException {
        QueryBuilder rewritten = filter.rewrite(queryRewriteContext);
        if (rewritten instanceof MatchNoneQueryBuilder) {
            return rewritten;
        }
        return rewritten != filter ? new KnnFilterQueryBuilder(vectorField, rewritten, rootScoped) : this;
    }

    @Override
    protected void doWriteTo(StreamOutput out) {
        throw new UnsupportedOperationException("[" + NAME + "] is never serialized");
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field("field", vectorField);
        builder.field("root_scoped", rootScoped);
        builder.field("filter");
        filter.toXContent(builder, params);
        boostAndQueryNameToXContent(builder);
        builder.endObject();
    }

    @Override
    protected boolean doEquals(KnnFilterQueryBuilder other) {
        return vectorField.equals(other.vectorField) && filter.equals(other.filter) && rootScoped == other.rootScoped;
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(vectorField, filter, rootScoped);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return filter.getMinimalSupportedVersion();
    }
}
