/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.util.Maps;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

public enum InferenceFieldType {
    SEMANTIC_TEXT("semantic_text"),
    SEMANTIC("semantic"),
    UNKNOWN("unknown");

    private static final Map<String, InferenceFieldType> NAME_TO_TYPE;
    static {
        InferenceFieldType[] values = values();
        Map<String, InferenceFieldType> nameToType = Maps.newHashMapWithExpectedSize(values.length);
        for (InferenceFieldType value : values) {
            nameToType.put(value.typeName, value);
        }
        NAME_TO_TYPE = Collections.unmodifiableMap(nameToType);
    }

    private final String typeName;

    InferenceFieldType(String typeName) {
        this.typeName = typeName;
    }

    public String getTypeName() {
        return typeName;
    }

    public static InferenceFieldType fromTypeName(String typeName) {
        return NAME_TO_TYPE.getOrDefault(typeName, UNKNOWN);
    }

    public static InferenceFieldType readFrom(StreamInput in) throws IOException {
        return InferenceFieldType.fromTypeName(in.readString());
    }

    public static void writeTo(StreamOutput out, InferenceFieldType value) throws IOException {
        out.writeString(value.getTypeName());
    }
}
