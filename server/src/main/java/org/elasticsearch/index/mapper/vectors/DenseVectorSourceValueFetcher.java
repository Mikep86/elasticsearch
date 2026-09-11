/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper.vectors;

import org.elasticsearch.index.mapper.SourceValueFetcher;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.search.lookup.Source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A {@link SourceValueFetcher} for {@code dense_vector} fields that returns values from {@code _source}
 * without parsing them — values arrive as numeric lists or as base64/hex-encoded strings and are passed
 * through as-is.
 */
class DenseVectorSourceValueFetcher extends SourceValueFetcher {
    private final Set<String> sourcePaths;

    DenseVectorSourceValueFetcher(String fieldName, SearchExecutionContext context) {
        super(fieldName, context);
        this.sourcePaths = context.isSourceEnabled() ? context.sourcePath(fieldName) : Collections.emptySet();
    }

    @Override
    public List<Object> fetchValues(Source source, int doc, List<Object> ignoredValues) {
        ArrayList<Object> values = new ArrayList<>();
        for (var path : sourcePaths) {
            Object sourceValue = source.extractValue(path, null);
            if (sourceValue == null) {
                return List.of();
            }
            switch (sourceValue) {
                case List<?> v -> values.addAll(v);
                case String s -> values.add(s);
                default -> ignoredValues.add(sourceValue);
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
