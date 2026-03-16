/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.elasticsearch.inference.ChunkingSettings;
import org.elasticsearch.inference.MinimalServiceSettings;
import org.elasticsearch.xcontent.ToXContentObject;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class InferenceResult<T extends InferenceChunk<T>, U extends InferenceResult<T, U>> implements ToXContentObject {
    protected final String inferenceId;
    protected final MinimalServiceSettings modelSettings;
    protected final ChunkingSettings chunkingSettings;
    protected final Map<String, List<T>> chunks;

    public InferenceResult(
        String inferenceId,
        MinimalServiceSettings modelSettings,
        ChunkingSettings chunkingSettings,
        Map<String, List<T>> chunks
    ) {
        this.inferenceId = inferenceId;
        this.modelSettings = modelSettings;
        this.chunkingSettings = chunkingSettings;
        this.chunks = chunks;
    }

    public String inferenceId() {
        return inferenceId;
    }

    public MinimalServiceSettings modelSettings() {
        return modelSettings;
    }

    public ChunkingSettings chunkingSettings() {
        return chunkingSettings;
    }

    public Map<String, List<T>> chunks() {
        return chunks;
    }

    protected abstract boolean doEquals(U other);

    protected abstract int doHashCode();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        U that = (U) o;
        return Objects.equals(inferenceId, that.inferenceId)
            && Objects.equals(modelSettings, that.modelSettings)
            && Objects.equals(chunkingSettings, that.chunkingSettings)
            && Objects.equals(chunks, that.chunks)
            && doEquals(that);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inferenceId, modelSettings, chunkingSettings, chunks, doHashCode());
    }
}
