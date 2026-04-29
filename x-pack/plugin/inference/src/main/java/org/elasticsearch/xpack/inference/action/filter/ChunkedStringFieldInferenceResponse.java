/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.action.filter;

import org.elasticsearch.core.Nullable;
import org.elasticsearch.inference.ChunkedInference;
import org.elasticsearch.inference.Model;

import java.util.Objects;

/**
 * A {@link FieldInferenceResponse} for chunked inference results produced from a single string input.
 */
final class ChunkedStringFieldInferenceResponse extends FieldInferenceResponse {
    /** The input that was used to run inference, or {@code null} when not retained (non-legacy format). */
    private final String input;
    /** The adjustment to apply to the chunk text offsets. */
    private final int offsetAdjustment;
    /** The actual chunked inference results. */
    private final ChunkedInference chunkedResults;

    ChunkedStringFieldInferenceResponse(
        String field,
        String sourceField,
        @Nullable String input,
        int inputOrder,
        int offsetAdjustment,
        @Nullable Model model,
        ChunkedInference chunkedResults
    ) {
        super(field, sourceField, inputOrder, model);
        this.input = input;
        this.offsetAdjustment = offsetAdjustment;
        this.chunkedResults = chunkedResults;
    }

    @Nullable
    public String input() {
        return input;
    }

    public int offsetAdjustment() {
        return offsetAdjustment;
    }

    public ChunkedInference chunkedResults() {
        return chunkedResults;
    }

    @Override
    public boolean equals(Object o) {
        if (super.equals(o) == false) return false;
        ChunkedStringFieldInferenceResponse that = (ChunkedStringFieldInferenceResponse) o;
        return offsetAdjustment == that.offsetAdjustment
            && Objects.equals(input, that.input)
            && Objects.equals(chunkedResults, that.chunkedResults);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), input, offsetAdjustment, chunkedResults);
    }
}
