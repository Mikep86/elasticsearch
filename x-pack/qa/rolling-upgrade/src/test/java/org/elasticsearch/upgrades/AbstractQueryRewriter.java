/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.upgrades;

import org.elasticsearch.action.MockResolvedIndices;
import org.elasticsearch.action.OriginalIndices;
import org.elasticsearch.action.ResolvedIndices;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.test.IndexSettingsModule;
import org.elasticsearch.xcontent.XContentParserConfiguration;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

public abstract class AbstractQueryRewriter {
    private final MapperService mapperService;
    private final ClientInvocationHandler invocationHandler;
    private final Client client;

    public AbstractQueryRewriter(MapperService mapperService) {
        this.mapperService = mapperService;
        this.invocationHandler = new ClientInvocationHandler();
        this.client = (Client) Proxy.newProxyInstance(Class.class.getClassLoader(), new Class<?>[] { Client.class }, invocationHandler);
    }

    public QueryBuilder rewrite(QueryBuilder queryBuilder, String indexName) throws IOException {
        // TODO: rewrite with search execution context as well
        QueryRewriteContext queryRewriteContext = createQueryRewriteContext(indexName);
        return queryBuilder.rewrite(queryRewriteContext);
    }

    public abstract boolean canSimulateMethod(Method method, Object[] args) throws NoSuchMethodException;

    public abstract Object simulateMethod(Method method, Object[] args);

    private QueryRewriteContext createQueryRewriteContext(String indexName) {
        IndexSettings indexSettings = createIndexSettings(indexName);
        ResolvedIndices resolvedIndices = createMockResolvedIndices(indexSettings);
        return new QueryRewriteContext(
            XContentParserConfiguration.EMPTY,
            client,
            System::currentTimeMillis,
            resolvedIndices,
            null,
            null,
            false
        );
    }

    private IndexSettings createIndexSettings(String indexName) {
        IndexMetadata indexMetadata = IndexMetadata.builder(indexName)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
                    .put(IndexMetadata.SETTING_INDEX_UUID, indexName)
            )
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putInferenceFields(mapperService.mappingLookup().inferenceFields())
            .build();

        return IndexSettingsModule.newIndexSettings(indexMetadata);
    }

    private ResolvedIndices createMockResolvedIndices(IndexSettings indexSettings) {
        return new MockResolvedIndices(
            Map.of(),
            new OriginalIndices(new String[] { indexSettings.getIndex().getName() }, IndicesOptions.DEFAULT),
            Map.of(indexSettings.getIndex(), indexSettings.getIndexMetadata())
        );
    }

    private class ClientInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (AbstractQueryRewriter.this.canSimulateMethod(method, args)) {
                return AbstractQueryRewriter.this.simulateMethod(method, args);
            }

            throw new UnsupportedOperationException("Can't handle calls to: " + method);
        }
    }
}
