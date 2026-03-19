/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.action.filter;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.cluster.metadata.InferenceFieldMetadata;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.mapper.InferenceMetadataFieldsMapper;
import org.elasticsearch.inference.ChunkingSettings;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xpack.core.inference.chunking.ChunkingSettingsBuilder;
import org.elasticsearch.xpack.inference.mapper.AbstractInferenceField;
import org.elasticsearch.xpack.inference.mapper.SemanticTextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.xpack.inference.action.filter.ShardBulkInferenceActionFilter.EMPTY_CHUNKED_INFERENCE;
import static org.elasticsearch.xpack.inference.action.filter.ShardBulkInferenceActionFilter.EXPLICIT_NULL;

class SemanticTextFieldIngest implements InferenceFieldIngest {
    @Override
    public List<ShardBulkInferenceActionFilter.FieldInferenceRequest> generateFieldInferenceRequests(
        ShardBulkInferenceActionFilter.AsyncBulkShardInferenceAction action,
        InferenceFieldMetadata inferenceFieldMetadata,
        ShardBulkInferenceActionFilter.AsyncBulkShardInferenceAction.IndexRequestWithIndexingPressure indexRequest,
        int itemIndex,
        boolean isUpdateRequest
    ) {
        final String field = inferenceFieldMetadata.getName();
        final ChunkingSettings chunkingSettings = ChunkingSettingsBuilder.fromMap(inferenceFieldMetadata.getChunkingSettings(), false);
        final boolean useLegacyFormat = InferenceMetadataFieldsMapper.isEnabled(action.getIndexSettings()) == false;
        final Map<String, Object> docMap = indexRequest.getIndexRequest().sourceAsMap();
        final List<ShardBulkInferenceActionFilter.FieldInferenceRequest> requests = new ArrayList<>();

        if (useLegacyFormat) {
            var originalFieldValue = XContentMapValues.extractValue(field, docMap);
            if (originalFieldValue instanceof Map || (originalFieldValue == null && inferenceFieldMetadata.getSourceFields().length == 1)) {
                // Inference has already been computed, or there is no inference required.
                return List.of();
            }
        } else {
            var inferenceMetadataFieldsValue = XContentMapValues.extractValue(
                InferenceMetadataFieldsMapper.NAME + "." + field,
                docMap,
                EXPLICIT_NULL
            );
            if (inferenceMetadataFieldsValue != null) {
                // Inference has already been computed
                return List.of();
            }
        }

        int order = 0;
        for (var sourceField : inferenceFieldMetadata.getSourceFields()) {
            var valueObj = XContentMapValues.extractValue(sourceField, docMap, EXPLICIT_NULL);
            if (useLegacyFormat == false && isUpdateRequest && valueObj == EXPLICIT_NULL) {
                /**
                 * It's an update request, and the source field is explicitly set to null,
                 * so we need to propagate this information to the inference fields metadata
                 * to overwrite any inference previously computed on the field.
                 * This ensures that the field is treated as intentionally cleared,
                 * preventing any unintended carryover of prior inference results.
                 */
                if (action.incrementIndexingPressurePreInference(indexRequest, itemIndex) == false) {
                    return List.of();
                }

                var slot = action.ensureResponseAccumulatorSlot(itemIndex);
                slot.addOrUpdateResponse(
                    new ShardBulkInferenceActionFilter.FieldInferenceResponse(
                        field,
                        sourceField,
                        null,
                        order++,
                        0,
                        null,
                        EMPTY_CHUNKED_INFERENCE
                    )
                );
                continue;
            }
            if (valueObj == null || valueObj == EXPLICIT_NULL) {
                if (isUpdateRequest && useLegacyFormat) {
                    action.setInferenceResponseFailure(
                        itemIndex,
                        new ElasticsearchStatusException(
                            "Field [{}] must be specified on an update request to calculate inference for field [{}]",
                            RestStatus.BAD_REQUEST,
                            sourceField,
                            field
                        )
                    );
                    break;
                }
                continue;
            }

            var slot = action.ensureResponseAccumulatorSlot(itemIndex);
            final List<String> values;
            try {
                values = SemanticTextUtils.nodeStringValues(field, valueObj);
            } catch (Exception exc) {
                action.setInferenceResponseFailure(itemIndex, exc);
                break;
            }

            int offsetAdjustment = 0;
            for (String v : values) {
                if (action.incrementIndexingPressurePreInference(indexRequest, itemIndex) == false) {
                    break;
                }

                if (v.isBlank()) {
                    slot.addOrUpdateResponse(
                        new ShardBulkInferenceActionFilter.FieldInferenceResponse(
                            field,
                            sourceField,
                            v,
                            order++,
                            0,
                            null,
                            EMPTY_CHUNKED_INFERENCE
                        )
                    );
                } else {
                    requests.add(
                        new ShardBulkInferenceActionFilter.FieldInferenceRequest(
                            itemIndex,
                            field,
                            sourceField,
                            v,
                            order++,
                            offsetAdjustment,
                            chunkingSettings
                        )
                    );
                }

                // When using the inference metadata fields format, all the input values are concatenated so that the
                // chunk text offsets are expressed in the context of a single string. Calculate the offset adjustment
                // to apply to account for this.
                offsetAdjustment += v.length() + 1; // Add one for separator char length
            }
        }

        return requests;
    }

    @Override
    public AbstractInferenceField<?, ?> processInferenceResponses(
        ShardBulkInferenceActionFilter.AsyncBulkShardInferenceAction action,
        InferenceFieldMetadata inferenceFieldMetadata,
        List<ShardBulkInferenceActionFilter.FieldInferenceResponse> responses
    ) {
        return null;
    }
}
