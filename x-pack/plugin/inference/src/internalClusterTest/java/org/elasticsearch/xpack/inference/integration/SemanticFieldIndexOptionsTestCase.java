/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.integration;

import org.elasticsearch.action.admin.indices.mapping.get.GetFieldMappingsAction;
import org.elasticsearch.action.admin.indices.mapping.get.GetFieldMappingsRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.index.mapper.vectors.IndexOptions;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.license.GetLicenseAction;
import org.elasticsearch.license.License;
import org.elasticsearch.license.LicenseSettings;
import org.elasticsearch.license.PostStartBasicAction;
import org.elasticsearch.license.PostStartBasicRequest;
import org.elasticsearch.license.PutLicenseAction;
import org.elasticsearch.license.PutLicenseRequest;
import org.elasticsearch.license.TestUtils;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.protocol.xpack.license.GetLicenseRequest;
import org.elasticsearch.reindex.ReindexPlugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.inference.action.DeleteInferenceEndpointAction;
import org.elasticsearch.xpack.inference.InferenceIndex;
import org.elasticsearch.xpack.inference.LocalStateInferencePlugin;
import org.elasticsearch.xpack.inference.mapper.ExtendedDenseVectorIndexOptions;
import org.elasticsearch.xpack.inference.mock.TestInferenceServicePlugin;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.CoreMatchers.equalTo;

/**
 * Base class for integration tests covering {@code index_options} serialization (including
 * {@code include_defaults} and element-type defaulting behavior) for semantic vector field types
 * ({@code semantic} and {@code semantic_text}). Provides shared cluster setup, license management,
 * inference-endpoint bookkeeping, and mapping/field-mapping helpers; subclasses supply the field
 * type under test and the actual test methods.
 */
@ESTestCase.WithoutEntitlements // due to dependency issue ES-12435
public abstract class SemanticFieldIndexOptionsTestCase extends ESIntegTestCase {
    protected static final String INDEX_NAME = "test-index";

    private final Map<String, TaskType> inferenceIds = new HashMap<>();

    /**
     * The {@code type} value ({@code semantic} or {@code semantic_text}) used when generating field mappings.
     */
    protected abstract String fieldType();

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder().put(LicenseSettings.SELF_GENERATED_LICENSE_TYPE.getKey(), "trial").build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(LocalStateInferencePlugin.class, TestInferenceServicePlugin.class, ReindexPlugin.class);
    }

    protected boolean forbidPrivateIndexSettings() {
        return false;
    }

    @Before
    public void resetLicense() throws Exception {
        setLicense(License.LicenseType.TRIAL);
    }

    @After
    public void cleanUp() {
        assertAcked(
            safeGet(
                client().admin()
                    .indices()
                    .prepareDelete(INDEX_NAME)
                    .setIndicesOptions(
                        IndicesOptions.builder().concreteTargetOptions(new IndicesOptions.ConcreteTargetOptions(true)).build()
                    )
                    .execute()
            )
        );

        for (var entry : inferenceIds.entrySet()) {
            assertAcked(
                safeGet(
                    client().execute(
                        DeleteInferenceEndpointAction.INSTANCE,
                        new DeleteInferenceEndpointAction.Request(entry.getKey(), entry.getValue(), true, false)
                    )
                )
            );
        }
    }

    protected void createInferenceEndpoint(TaskType taskType, String inferenceId, Map<String, Object> serviceSettings) throws IOException {
        IntegrationTestUtils.createInferenceEndpoint(client(), taskType, inferenceId, serviceSettings);
        inferenceIds.put(inferenceId, taskType);
    }

    protected Settings indexSettingsWithVersion(IndexVersion version) {
        return Settings.builder().put(indexSettings()).put(IndexMetadata.SETTING_VERSION_CREATED, version).build();
    }

    protected XContentBuilder generateMapping(String inferenceFieldName, String inferenceId, @Nullable IndexOptions indexOptions)
        throws IOException {
        XContentBuilder mapping = XContentFactory.jsonBuilder();
        mapping.startObject();
        mapping.field("properties");
        generateFieldMapping(mapping, inferenceFieldName, inferenceId, indexOptions);
        mapping.endObject();

        return mapping;
    }

    protected void generateFieldMapping(
        XContentBuilder builder,
        String inferenceFieldName,
        String inferenceId,
        @Nullable IndexOptions indexOptions
    ) throws IOException {
        builder.startObject();
        builder.startObject(inferenceFieldName);
        builder.field("type", fieldType());
        builder.field("inference_id", inferenceId);
        if (indexOptions != null) {
            builder.startObject("index_options");
            if (indexOptions instanceof DenseVectorFieldMapper.DenseVectorIndexOptions
                || indexOptions instanceof ExtendedDenseVectorIndexOptions) {
                builder.field("dense_vector");
                indexOptions.toXContent(builder, ToXContent.EMPTY_PARAMS);
            }
            builder.endObject();
        }
        builder.endObject();
        builder.endObject();
    }

    protected Map<String, Object> generateExpectedFieldMapping(
        String inferenceFieldName,
        String inferenceId,
        @Nullable IndexOptions indexOptions
    ) throws IOException {
        Map<String, Object> expectedFieldMapping;
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            generateFieldMapping(builder, inferenceFieldName, inferenceId, indexOptions);
            expectedFieldMapping = XContentHelper.convertToMap(BytesReference.bytes(builder), false, XContentType.JSON).v2();
        }

        return expectedFieldMapping;
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> filterNullOrEmptyValues(Map<String, Object> map) {
        Map<String, Object> filteredMap = new HashMap<>();
        for (var entry : map.entrySet()) {
            Object value = entry.getValue();
            if (entry.getValue() instanceof Map<?, ?> mapValue) {
                if (mapValue.isEmpty()) {
                    continue;
                }

                value = filterNullOrEmptyValues((Map<String, Object>) mapValue);
            }

            if (value != null) {
                filteredMap.put(entry.getKey(), value);
            }
        }

        return filteredMap;
    }

    protected static Map<String, Object> getFieldMappings(String fieldName, boolean includeDefaults) {
        var request = new GetFieldMappingsRequest().indices(INDEX_NAME).fields(fieldName).includeDefaults(includeDefaults);
        return safeGet(client().execute(GetFieldMappingsAction.INSTANCE, request)).fieldMappings(INDEX_NAME, fieldName).sourceAsMap();
    }

    protected static void setLicense(License.LicenseType type) throws Exception {
        if (type == License.LicenseType.BASIC) {
            assertAcked(
                safeGet(
                    client().execute(
                        PostStartBasicAction.INSTANCE,
                        new PostStartBasicRequest(TEST_REQUEST_TIMEOUT, TEST_REQUEST_TIMEOUT).acknowledge(true)
                    )
                )
            );
        } else {
            License license = TestUtils.generateSignedLicense(
                type.getTypeName(),
                License.VERSION_CURRENT,
                -1,
                TimeValue.timeValueHours(24)
            );
            assertAcked(
                safeGet(
                    client().execute(
                        PutLicenseAction.INSTANCE,
                        new PutLicenseRequest(TEST_REQUEST_TIMEOUT, TEST_REQUEST_TIMEOUT).license(license)
                    )
                )
            );
        }
    }

    protected static void assertLicense(License.LicenseType type) {
        var getLicenseResponse = safeGet(client().execute(GetLicenseAction.INSTANCE, new GetLicenseRequest(TEST_REQUEST_TIMEOUT)));
        assertThat(getLicenseResponse.license().type(), equalTo(type.getTypeName()));
    }

    protected void downgradeLicenseAndRestartCluster() throws Exception {
        // Downgrade the license and restart the cluster to force the model registry to rebuild
        setLicense(License.LicenseType.BASIC);
        internalCluster().fullRestart(new InternalTestCluster.RestartCallback());
        ensureGreen(InferenceIndex.INDEX_NAME);
        assertLicense(License.LicenseType.BASIC);
    }
}
