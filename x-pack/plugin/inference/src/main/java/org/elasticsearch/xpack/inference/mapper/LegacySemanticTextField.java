/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.elasticsearch.core.Nullable;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.xcontent.ConstructingObjectParser.optionalConstructorArg;

public class LegacySemanticTextField extends SemanticField {
    static final String TEXT_FIELD = "text";

    @SuppressWarnings("unchecked")
    private static final ConstructingObjectParser<LegacySemanticTextField, SemanticParserContext> PARSER = new ConstructingObjectParser<>(
        "legacy_semantic_text_field",
        true,
        (args, context) -> {
            List<String> originalValues = (List<String>) args[0];
            LegacySemanticTextInferenceResult inference = (LegacySemanticTextInferenceResult) args[1];
            return new LegacySemanticTextField(context.fieldName(), inference, originalValues);
        }
    );

    static {
        PARSER.declareStringArray(optionalConstructorArg(), new ParseField(TEXT_FIELD));
        PARSER.declareObject(constructorArg(), (p, c) -> LegacySemanticTextInferenceResult.parse(c, p), new ParseField(INFERENCE_FIELD));
    }

    static LegacySemanticTextField parse(SemanticParserContext context, XContentParser parser) throws IOException {
        return PARSER.parse(parser, context);
    }

    private final List<String> originalValues;

    public LegacySemanticTextField(String fieldName, SemanticInferenceResult inference, @Nullable List<String> originalValues) {
        super(fieldName, inference);
        this.originalValues = originalValues;
    }

    public List<String> originalValues() {
        return originalValues != null ? Collections.unmodifiableList(originalValues) : Collections.emptyList();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        List<String> values = originalValues();
        if (values.isEmpty() == false) {
            builder.field(TEXT_FIELD, values.size() == 1 ? values.getFirst() : values);
        }
        toXContentInference(builder, params);
        builder.endObject();
        return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (super.equals(o) == false) return false;
        LegacySemanticTextField that = (LegacySemanticTextField) o;
        return Objects.equals(originalValues, that.originalValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), originalValues);
    }

    @Override
    protected void validateInference(SemanticInferenceResult inference) {
        if (inference instanceof LegacySemanticTextInferenceResult == false) {
            throw new IllegalStateException("Inference must use legacy format");
        }
    }
}
