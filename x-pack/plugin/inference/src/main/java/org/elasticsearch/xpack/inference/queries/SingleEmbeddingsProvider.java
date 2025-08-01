/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.queries;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.inference.InferenceResults;

import java.io.IOException;
import java.util.Objects;

public class SingleEmbeddingsProvider implements EmbeddingsProvider {
    public static final String NAME = "single_embeddings_provider";

    private final InferenceResults embeddings;

    public SingleEmbeddingsProvider(InferenceResults embeddings) {
        this.embeddings = embeddings;
    }

    public SingleEmbeddingsProvider(StreamInput in) throws IOException {
        this.embeddings = in.readNamedWriteable(InferenceResults.class);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeNamedWriteable(embeddings);
    }

    @Override
    public InferenceResults getEmbeddings(InferenceEndpointKey key) {
        return embeddings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SingleEmbeddingsProvider that = (SingleEmbeddingsProvider) o;
        return Objects.equals(embeddings, that.embeddings);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(embeddings);
    }
}
