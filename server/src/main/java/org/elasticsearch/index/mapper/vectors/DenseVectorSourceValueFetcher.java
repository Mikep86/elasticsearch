/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper.vectors;

import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.mapper.NumberFieldMapper;
import org.elasticsearch.index.mapper.SourceValueFetcher;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper.ElementType;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.search.lookup.Source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A {@link SourceValueFetcher} for {@code dense_vector} fields.
 */
class DenseVectorSourceValueFetcher extends SourceValueFetcher {

    private final Set<String> sourcePaths;
    private final ElementType elementType;
    @Nullable
    private final Integer dims;
    private final boolean decodeEncodedVectors;

    DenseVectorSourceValueFetcher(
        String fieldName,
        SearchExecutionContext context,
        ElementType elementType,
        @Nullable Integer dims,
        boolean decodeEncodedVectors
    ) {
        super(fieldName, context);
        this.sourcePaths = context.isSourceEnabled() ? context.sourcePath(fieldName) : Collections.emptySet();
        this.elementType = elementType;
        this.dims = dims;
        this.decodeEncodedVectors = decodeEncodedVectors;
    }

    @Override
    public List<Object> fetchValues(Source source, int doc, List<Object> ignoredValues) {
        List<Object> values = null;
        for (var path : sourcePaths) {
            Object sourceValue = source.extractValue(path, null);
            if (sourceValue == null) {
                continue;
            }
            if (values != null) {
                // A dense_vector holds exactly one vector, so the first value found wins. A further value is
                // only reachable when this field is a copy_to target; report it as ignored rather than
                // merging it into the vector.
                ignoredValues.add(sourceValue);
                continue;
            }
            values = decodeEncodedVectors ? decodedValues(sourceValue, ignoredValues) : rawValues(sourceValue, ignoredValues);
        }
        return values == null ? List.of() : values;
    }

    /**
     * Pass-through: returns source values without parsing. Used for {@code format: null}.
     *
     * @return the values, or {@code null} if this source value yielded no vector
     */
    @Nullable
    private static List<Object> rawValues(Object sourceValue, List<Object> ignoredValues) {
        switch (sourceValue) {
            case List<?> v -> {
                return new ArrayList<>(v);
            }
            case String s -> {
                return List.of(s);
            }
            default -> {
                ignoredValues.add(sourceValue);
                return null;
            }
        }
    }

    /**
     * Normalizes source values to {@code Float}. Used for {@code format: "array"}.
     *
     * @return the values, or {@code null} if this source value yielded no vector
     */
    @Nullable
    private List<Object> decodedValues(Object sourceValue, List<Object> ignoredValues) {
        try {
            switch (sourceValue) {
                case List<?> v -> {
                    List<Object> values = new ArrayList<>(v.size());
                    for (Object o : v) {
                        values.add(NumberFieldMapper.NumberType.FLOAT.parse(o, false));
                    }
                    return values;
                }
                case String s -> {
                    if (dims == null) {
                        // Dimensions are unknown until the first document is indexed; nothing to decode against.
                        ignoredValues.add(s);
                        return null;
                    }
                    return DecodedVector.decode(s, elementType, dims).toFloatList();
                }
                default -> ignoredValues.add(sourceValue);
            }
        } catch (Exception e) {
            // if parsing fails here then it would have failed at index time
            // as well, meaning that we must be ignoring malformed values.
            ignoredValues.add(sourceValue);
        }
        return null;
    }

    @Override
    protected Object parseSourceValue(Object value) {
        throw new IllegalStateException("parsing dense vector from source is not supported here");
    }
}
