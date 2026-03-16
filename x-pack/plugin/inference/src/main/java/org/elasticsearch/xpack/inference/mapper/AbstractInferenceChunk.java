/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentType;

import java.util.Objects;

public abstract class AbstractInferenceChunk<T extends AbstractInferenceChunk<T>> implements ToXContentObject {
    protected final BytesReference rawEmbeddings;
    protected final XContentType contentType;

    public AbstractInferenceChunk(BytesReference rawEmbeddings, XContentType contentType) {
        this.rawEmbeddings = Objects.requireNonNull(rawEmbeddings);
        this.contentType = Objects.requireNonNull(contentType);
    }

    public BytesReference rawEmbeddings() {
        return rawEmbeddings;
    }

    public XContentType contentType() {
        return contentType;
    }

    protected abstract boolean doEquals(T other);

    protected abstract int doHashCode();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        T that = (T) o;
        return Objects.equals(rawEmbeddings, that.rawEmbeddings) && contentType == that.contentType && doEquals(that);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawEmbeddings, contentType, doHashCode());
    }
}
