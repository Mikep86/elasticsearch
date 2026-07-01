/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.integration;

import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.index.mapper.vectors.IndexOptions;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.test.index.IndexVersionUtils;
import org.elasticsearch.xpack.inference.mapper.ExtendedDenseVectorIndexOptions;
import org.elasticsearch.xpack.inference.mapper.SemanticTextFieldMapper;

import java.util.Map;

import static org.elasticsearch.index.IndexVersions.SEMANTIC_TEXT_DEFAULTS_TO_BBQ;
import static org.elasticsearch.index.IndexVersions.SEMANTIC_TEXT_DEFAULTS_TO_BFLOAT16;
import static org.elasticsearch.index.IndexVersions.SEMANTIC_TEXT_USES_DENSE_VECTOR_DEFAULT_INDEX_OPTIONS;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;

public class SemanticTextIndexOptionsIT extends SemanticFieldIndexOptionsTestCase {

    private static final Map<String, Object> BBQ_COMPATIBLE_SERVICE_SETTINGS = Map.of(
        "model",
        "my_model",
        "dimensions",
        256,
        "similarity",
        "cosine",
        "api_key",
        "my_api_key"
    );

    private static final Map<String, Object> BFLOAT16_SERVICE_SETTINGS = Map.of(
        "model",
        "my_model",
        "dimensions",
        256,
        "similarity",
        "cosine",
        "api_key",
        "my_api_key",
        "element_type",
        "bfloat16"
    );

    @Override
    protected String fieldType() {
        return SemanticTextFieldMapper.CONTENT_TYPE;
    }

    public void testValidateIndexOptionsWithBasicLicense() throws Exception {
        final String inferenceId = randomIdentifier();
        final String inferenceFieldName = "inference_field";
        createInferenceEndpoint(TaskType.TEXT_EMBEDDING, inferenceId, BBQ_COMPATIBLE_SERVICE_SETTINGS);
        downgradeLicenseAndRestartCluster();

        IndexOptions indexOptions = new DenseVectorFieldMapper.Int8HnswIndexOptions(
            randomIntBetween(1, 100),
            randomIntBetween(1, 10_000),
            randomBoolean(),
            null,
            -1
        );
        assertAcked(
            safeGet(prepareCreate(INDEX_NAME).setMapping(generateMapping(inferenceFieldName, inferenceId, indexOptions)).execute())
        );

        final Map<String, Object> expectedFieldMapping = generateExpectedFieldMapping(inferenceFieldName, inferenceId, indexOptions);
        assertThat(getFieldMappings(inferenceFieldName, false), equalTo(expectedFieldMapping));
    }

    public void testSetDefaultBBQIndexOptionsWithBasicLicense() throws Exception {
        final String inferenceId = randomIdentifier();
        final String inferenceFieldName = "inference_field";
        createInferenceEndpoint(TaskType.TEXT_EMBEDDING, inferenceId, BBQ_COMPATIBLE_SERVICE_SETTINGS);
        downgradeLicenseAndRestartCluster();

        for (int i = 0; i < 20; i++) {
            IndexVersion indexVersion = IndexVersionUtils.randomVersionBetween(
                SEMANTIC_TEXT_DEFAULTS_TO_BBQ,
                IndexVersionUtils.getPreviousVersion(SEMANTIC_TEXT_USES_DENSE_VECTOR_DEFAULT_INDEX_OPTIONS)
            );
            assertAcked(
                safeGet(
                    prepareCreate(INDEX_NAME).setSettings(indexSettingsWithVersion(indexVersion))
                        .setMapping(generateMapping(inferenceFieldName, inferenceId, null))
                        .execute()
                )
            );

            final Map<String, Object> expectedFieldMapping = generateExpectedFieldMapping(
                inferenceFieldName,
                inferenceId,
                indexVersion.onOrAfter(SEMANTIC_TEXT_DEFAULTS_TO_BFLOAT16)
                    ? new ExtendedDenseVectorIndexOptions(
                        SemanticTextFieldMapper.defaultBbqHnswDenseVectorIndexOptions(),
                        DenseVectorFieldMapper.ElementType.BFLOAT16
                    )
                    : SemanticTextFieldMapper.defaultBbqHnswDenseVectorIndexOptions()
            );

            Map<String, Object> actualFieldMappings = filterNullOrEmptyValues(getFieldMappings(inferenceFieldName, true));
            assertThat("indexVersion = " + indexVersion, actualFieldMappings, equalTo(expectedFieldMapping));

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
        }
    }

    public void testGetDefaultIndexOptionsWithElementTypeOverride() throws Exception {
        final String inferenceId = randomIdentifier();
        final String inferenceFieldName = "inference_field";

        // Create the index before the inference endpoint exists. Default index options cannot be determined yet.
        assertAcked(safeGet(prepareCreate(INDEX_NAME).setMapping(generateMapping(inferenceFieldName, inferenceId, null)).execute()));
        Map<String, Object> actualFieldMappings = getFieldMappings(inferenceFieldName, true);

        Map<String, Object> inferenceFieldMappings = XContentMapValues.nodeMapValue(
            actualFieldMappings.get(inferenceFieldName),
            inferenceFieldName
        );
        assertThat(inferenceFieldMappings.containsKey("index_options"), is(true));
        assertThat(inferenceFieldMappings.get("index_options"), nullValue());

        // Create the inference endpoint
        createInferenceEndpoint(TaskType.TEXT_EMBEDDING, inferenceId, BBQ_COMPATIBLE_SERVICE_SETTINGS);

        // We should now be able to get the default index options
        final Map<String, Object> expectedFieldMappingWithDefaults = generateExpectedFieldMapping(
            inferenceFieldName,
            inferenceId,
            new ExtendedDenseVectorIndexOptions(null, DenseVectorFieldMapper.ElementType.BFLOAT16)
        );

        actualFieldMappings = filterNullOrEmptyValues(getFieldMappings(inferenceFieldName, true));
        assertThat(actualFieldMappings, equalTo(expectedFieldMappingWithDefaults));

        // If we exclude defaults, index options should not be returned
        final Map<String, Object> expectedFieldMappingWithoutDefaults = generateExpectedFieldMapping(inferenceFieldName, inferenceId, null);

        actualFieldMappings = getFieldMappings(inferenceFieldName, false);
        assertThat(actualFieldMappings, equalTo(expectedFieldMappingWithoutDefaults));
    }

    public void testSerializeDefaultToBfloat16WithExplicitType() throws Exception {
        final String inferenceId = randomIdentifier();
        final String inferenceFieldName = "inference_field";
        createInferenceEndpoint(TaskType.TEXT_EMBEDDING, inferenceId, BBQ_COMPATIBLE_SERVICE_SETTINGS);

        DenseVectorFieldMapper.DenseVectorIndexOptions baseIndexOptions = new DenseVectorFieldMapper.Int4HnswIndexOptions(
            20,
            90,
            false,
            null,
            -1
        );
        assertAcked(
            safeGet(prepareCreate(INDEX_NAME).setMapping(generateMapping(inferenceFieldName, inferenceId, baseIndexOptions)).execute())
        );

        final Map<String, Object> expectedFieldMappingWithoutDefaults = generateExpectedFieldMapping(
            inferenceFieldName,
            inferenceId,
            baseIndexOptions
        );
        final Map<String, Object> expectedFieldMappingWithDefaults = generateExpectedFieldMapping(
            inferenceFieldName,
            inferenceId,
            new ExtendedDenseVectorIndexOptions(baseIndexOptions, DenseVectorFieldMapper.ElementType.BFLOAT16)
        );

        // When include_defaults == false, the BFLOAT16 default should not be serialized
        Map<String, Object> actualFieldMappings = filterNullOrEmptyValues(getFieldMappings(inferenceFieldName, false));
        assertThat(actualFieldMappings, equalTo(expectedFieldMappingWithoutDefaults));

        // When include_defaults == true, the BFLOAT16 default should be serialized
        actualFieldMappings = filterNullOrEmptyValues(getFieldMappings(inferenceFieldName, true));
        assertThat(actualFieldMappings, equalTo(expectedFieldMappingWithDefaults));
    }

    public void testElementTypeExcludedFromDefaultIndexOptionsWhenNoOverride() throws Exception {
        final String inferenceId = randomIdentifier();
        final String inferenceFieldName = "inference_field";
        createInferenceEndpoint(TaskType.TEXT_EMBEDDING, inferenceId, BFLOAT16_SERVICE_SETTINGS);
        assertAcked(safeGet(prepareCreate(INDEX_NAME).setMapping(generateMapping(inferenceFieldName, inferenceId, null)).execute()));

        // If we didn't default to bfloat16, element_type should be excluded from index options even when include_defaults is true
        final Map<String, Object> expectedFieldMapping = generateExpectedFieldMapping(inferenceFieldName, inferenceId, null);

        Map<String, Object> actualFieldMappings = filterNullOrEmptyValues(getFieldMappings(inferenceFieldName, true));
        assertThat(actualFieldMappings, equalTo(expectedFieldMapping));
    }
}
