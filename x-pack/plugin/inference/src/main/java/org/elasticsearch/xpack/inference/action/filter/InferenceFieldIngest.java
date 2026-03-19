/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.action.filter;

import org.elasticsearch.cluster.metadata.InferenceFieldMetadata;
import org.elasticsearch.xpack.inference.mapper.AbstractInferenceField;

import java.util.List;

interface InferenceFieldIngest {
    List<ShardBulkInferenceActionFilter.FieldInferenceRequest> generateFieldInferenceRequests(
        ShardBulkInferenceActionFilter.AsyncBulkShardInferenceAction action,
        InferenceFieldMetadata inferenceFieldMetadata,
        ShardBulkInferenceActionFilter.AsyncBulkShardInferenceAction.IndexRequestWithIndexingPressure indexRequest,
        int itemIndex,
        boolean isUpdateRequest
    );

    AbstractInferenceField<?, ?> processInferenceResponses(
        ShardBulkInferenceActionFilter.AsyncBulkShardInferenceAction action,
        InferenceFieldMetadata inferenceFieldMetadata,
        List<ShardBulkInferenceActionFilter.FieldInferenceResponse> responses
    );
}
