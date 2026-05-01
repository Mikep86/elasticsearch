/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.core.CheckedFunction;
import org.elasticsearch.inference.InferenceString;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.DeprecationHandler;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.support.MapXContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class SemanticTextUtils {
    private static final String STRING_EXPECTED_TYPES = "String|Number|Boolean";
    private static final String OBJECT_EXPECTED_TYPES = STRING_EXPECTED_TYPES + "|InferenceString";

    private SemanticTextUtils() {}

    /**
     * Normalizes a raw source value extracted from an inference field source field into a flat list of inference inputs,
     * preserving order.
     *
     * <p>Each element in the returned list is one of:
     * <ul>
     *     <li>a {@link String} — for raw {@code String}, {@code Number}, or {@code Boolean} input values</li>
     *     <li>an {@link org.elasticsearch.inference.InferenceString} — for input values supplied as an object</li>
     * </ul>
     *
     * <p>If {@code valueObj} is a {@link Collection}, every element is converted in iteration order. Any other {@code valueObj}
     * is converted as a single-element list.
     *
     * @param field    the source field name
     * @param valueObj the raw source field value
     * @return a flat list of inference inputs
     * @throws ElasticsearchStatusException if the raw source field value uses an invalid format
     * @throws IOException if {@link InferenceString} parsing fails
     */
    @SuppressWarnings("unchecked")
    public static List<Object> nodeObjectValues(String field, Object valueObj) throws IOException {
        return (List<Object>) nodeObjectValues(field, valueObj, true);
    }

    /**
     * Normalizes a raw source value extracted from an inference field source field into a flat list of string inference inputs,
     * preserving order.
     *
     * <p>If {@code valueObj} is a {@link Collection}, every element is converted in iteration order. Any other {@code valueObj}
     * is converted as a single-element list.
     *
     * @param field    the source field name
     * @param valueObj the raw source field value
     * @return a list of string inference inputs
     * @throws ElasticsearchStatusException if the raw source field value uses an invalid format
     * @throws IOException if {@link InferenceString} parsing fails
     */
    @SuppressWarnings("unchecked")
    public static List<String> nodeStringValues(String field, Object valueObj) throws IOException {
        return (List<String>) nodeObjectValues(field, valueObj, false);
    }

    private static List<?> nodeObjectValues(String field, Object valueObj, boolean parseInferenceStrings) throws IOException {
        final CheckedFunction<Object, Object, IOException> parseRawValue = raw -> {
            Object parsed = nodeObjectValue(field, raw, parseInferenceStrings);
            assert parseInferenceStrings || parsed instanceof String : "All values for field [" + field + "] must be strings";
            return parsed;
        };

        List<Object> parsedValues;
        if (valueObj instanceof Collection<?> values) {
            parsedValues = new ArrayList<>(values.size());
            for (var v : values) {
                parsedValues.add(parseRawValue.apply(v));
            }
        } else {
            parsedValues = List.of(parseRawValue.apply(valueObj));
        }

        return parsedValues;
    }

    private static Object nodeObjectValue(String field, Object valueObj, boolean parseInferenceStrings) throws IOException {
        if (valueObj instanceof Number || valueObj instanceof Boolean) {
            return valueObj.toString();
        } else if (valueObj instanceof String value) {
            return value;
        } else if (parseInferenceStrings && valueObj instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stringKeyedMap = (Map<String, Object>) map;
            return parseInferenceStringValue(stringKeyedMap);
        } else {
            throw new ElasticsearchStatusException(
                "Invalid format for field [{}], expected [{}] got [{}]",
                RestStatus.BAD_REQUEST,
                field,
                parseInferenceStrings ? OBJECT_EXPECTED_TYPES : STRING_EXPECTED_TYPES,
                valueObj.getClass().getSimpleName()
            );
        }
    }

    private static InferenceString parseInferenceStringValue(Map<String, Object> value) throws IOException {
        // TODO: Throw more descriptive exception if inference string is invalid?
        try (
            XContentParser parser = new MapXContentParser(
                NamedXContentRegistry.EMPTY,
                DeprecationHandler.IGNORE_DEPRECATIONS,
                value,
                XContentType.JSON
            )
        ) {
            return InferenceString.PARSER.parse(parser, null);
        }
    }
}
