/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentType;

import java.util.Objects;

public abstract class AbstractInferenceField<T extends AbstractInferenceResult<?, T>, U extends AbstractInferenceField<T, U>>
    implements
        ToXContentObject {
    protected final String fieldName;
    protected final T inference;
    protected final XContentType contentType;

    public AbstractInferenceField(String fieldName, T inference, XContentType contentType) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.inference = Objects.requireNonNull(inference);
        this.contentType = Objects.requireNonNull(contentType);
    }

    public String fieldName() {
        return fieldName;
    }

    public T inference() {
        return inference;
    }

    public XContentType contentType() {
        return contentType;
    }

    protected abstract boolean doEquals(U other);

    protected abstract int doHashCode();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        U that = (U) o;
        return Objects.equals(fieldName, that.fieldName)
            && Objects.equals(inference, that.inference)
            && contentType == that.contentType
            && doEquals(that);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, inference, contentType, doHashCode());
    }
}
