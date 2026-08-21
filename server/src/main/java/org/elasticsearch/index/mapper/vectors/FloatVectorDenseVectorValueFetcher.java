/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper.vectors;

import org.elasticsearch.index.mapper.IgnoredSourceFieldMapper.IgnoredSourceFormat;
import org.elasticsearch.index.mapper.NumberFieldMapper;
import org.elasticsearch.index.mapper.SourceValueFetcher;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper.ByteElement;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper.ElementType;
import org.elasticsearch.search.lookup.Source;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * A {@link SourceValueFetcher} for {@code dense_vector} fields that decodes source values into
 * a list of floats.
 */
class FloatVectorDenseVectorValueFetcher extends SourceValueFetcher {

    private final Set<String> sourcePaths;
    private final ElementType elementType;
    private final int dims;

    FloatVectorDenseVectorValueFetcher(
        Set<String> sourcePaths,
        IgnoredSourceFormat ignoredSourceFormat,
        ElementType elementType,
        int dims
    ) {
        super(sourcePaths, null, ignoredSourceFormat);
        this.sourcePaths = sourcePaths;
        this.elementType = elementType;
        this.dims = dims;
    }

    @Override
    public List<Object> fetchValues(Source source, int doc, List<Object> ignoredValues) {
        ArrayList<Object> values = new ArrayList<>();
        for (var path : sourcePaths) {
            Object sourceValue = source.extractValue(path, null);
            if (sourceValue == null) {
                return List.of();
            }
            try {
                switch (sourceValue) {
                    case List<?> v -> {
                        for (Object o : v) {
                            values.add(NumberFieldMapper.NumberType.FLOAT.parse(o, false));
                        }
                    }
                    case String s -> {
                        if ((elementType == ElementType.BYTE || elementType == ElementType.BIT)
                            && s.length() == dims * 2
                            && ByteElement.isMaybeHexString(s)) {
                            byte[] bytes;
                            try {
                                bytes = HexFormat.of().parseHex(s);
                            } catch (IllegalArgumentException e) {
                                bytes = Base64.getDecoder().decode(s);
                            }
                            for (byte b : bytes) {
                                values.add((float) b);
                            }
                        } else {
                            byte[] floatBytes = Base64.getDecoder().decode(s);
                            float[] floats = new float[dims];
                            ByteBuffer.wrap(floatBytes).asFloatBuffer().get(floats);
                            for (float f : floats) {
                                values.add(f);
                            }
                        }
                    }
                    default -> ignoredValues.add(sourceValue);
                }
            } catch (Exception e) {
                // if parsing fails here then it would have failed at index time
                // as well, meaning that we must be ignoring malformed values.
                ignoredValues.add(sourceValue);
            }
        }
        values.trimToSize();
        return values;
    }

    @Override
    protected Object parseSourceValue(Object value) {
        throw new IllegalStateException("parsing dense vector from source is not supported here");
    }
}
