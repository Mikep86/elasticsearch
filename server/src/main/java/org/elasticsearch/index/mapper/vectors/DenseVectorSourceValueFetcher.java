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
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.search.lookup.Source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.common.Strings.format;

/**
 * A {@link SourceValueFetcher} for {@code dense_vector} fields.
 */
class DenseVectorSourceValueFetcher extends SourceValueFetcher {
    private static final Logger logger = LogManager.getLogger(DenseVectorSourceValueFetcher.class);

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
            try {
                if (values != null) {
                    // A dense_vector holds exactly one vector, so the first value found wins. A further
                    // value is only reachable when this field is the target of a copy_to.
                    throw new IllegalStateException("a dense_vector holds a single vector and one has already been found");
                }
                values = decodeEncodedVectors ? decodedValues(sourceValue) : rawValues(sourceValue);
            } catch (Exception e) {
                // if parsing fails here then it would have failed at index time
                // as well, meaning that we must be ignoring malformed values.
                ignoredValues.add(sourceValue);
                logger.debug(() -> format("ignoring dense vector value from source path [%s]", path), e);
            }
        }
        return values == null ? List.of() : values;
    }

    /**
     * Pass-through: returns source values without parsing. Used for {@code format: null}.
     */
    private static List<Object> rawValues(Object sourceValue) {
        return switch (sourceValue) {
            case List<?> v -> new ArrayList<>(v);
            case String s -> List.of(s);
            default -> throw unsupportedSourceValue(sourceValue);
        };
    }

    /**
     * Normalizes source values to {@code Float}. Used for {@code format: "array"}.
     */
    private List<Object> decodedValues(Object sourceValue) {
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
                    throw new IllegalStateException("dimensions are unknown because no document has been indexed yet");
                }
                return DecodedVector.decode(s, elementType, dims).toFloatList();
            }
            default -> throw unsupportedSourceValue(sourceValue);
        }
    }

    private static IllegalArgumentException unsupportedSourceValue(Object sourceValue) {
        return new IllegalArgumentException("unsupported source value type [" + sourceValue.getClass().getSimpleName() + "]");
    }

    @Override
    protected Object parseSourceValue(Object value) {
        throw new IllegalStateException("parsing dense vector from source is not supported here");
    }
}
