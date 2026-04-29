/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.action.filter;

import org.elasticsearch.core.Nullable;
import org.elasticsearch.inference.InferenceServiceResults;
import org.elasticsearch.inference.InferenceString;
import org.elasticsearch.inference.Model;

import java.util.Objects;

/**
 * A {@link FieldInferenceResponse} for inference results produced from a single typed {@link InferenceString} input.
 */
final class InferenceStringFieldInferenceResponse extends FieldInferenceResponse {
    /** The position of the input within its source. */
    private final int inputIndex;
    /** the inference results. */
    private final InferenceServiceResults inferenceResults;

    InferenceStringFieldInferenceResponse(
        String field,
        String sourceField,
        int inputOrder,
        int inputIndex,
        @Nullable Model model,
        InferenceServiceResults inferenceResults
    ) {
        super(field, sourceField, inputOrder, model);
        this.inputIndex = inputIndex;
        this.inferenceResults = inferenceResults;
    }

    public int inputIndex() {
        return inputIndex;
    }

    public InferenceServiceResults inferenceResults() {
        return inferenceResults;
    }

    @Override
    public boolean equals(Object o) {
        if (super.equals(o) == false) return false;
        InferenceStringFieldInferenceResponse that = (InferenceStringFieldInferenceResponse) o;
        return inputIndex == that.inputIndex && Objects.equals(inferenceResults, that.inferenceResults);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), inputIndex, inferenceResults);
    }
}
