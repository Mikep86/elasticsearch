/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.action.filter;

import org.elasticsearch.inference.InferenceString;

import java.util.Objects;

/**
 * A {@link FieldInferenceRequest} for a single typed {@link InferenceString} input.
 */
final class InferenceStringFieldInferenceRequest extends FieldInferenceRequest {
    /** The input to run inference on. */
    private final InferenceString input;
    /** The position of this input within its source. */
    private final int inputIndex;

    InferenceStringFieldInferenceRequest(
        int bulkItemIndex,
        String field,
        String sourceField,
        InferenceString input,
        int inputOrder,
        int inputIndex
    ) {
        super(bulkItemIndex, field, sourceField, inputOrder);
        this.input = input;
        this.inputIndex = inputIndex;
    }

    public InferenceString input() {
        return input;
    }

    public int inputIndex() {
        return inputIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (super.equals(o) == false) return false;
        InferenceStringFieldInferenceRequest that = (InferenceStringFieldInferenceRequest) o;
        return inputIndex == that.inputIndex && Objects.equals(input, that.input);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), input, inputIndex);
    }
}
