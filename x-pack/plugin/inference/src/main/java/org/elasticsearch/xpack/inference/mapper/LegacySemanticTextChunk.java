/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

public class LegacySemanticTextChunk extends SemanticChunk {
    static final String CHUNKED_TEXT_FIELD = "text";

    private static final ConstructingObjectParser<LegacySemanticTextChunk, SemanticParserContext> PARSER = new ConstructingObjectParser<>(
        "legacy_semantic_text_chunk",
        true,
        (args, context) -> new LegacySemanticTextChunk((String) args[0], (BytesReference) args[1], context.xContentType())
    );

    static {
        PARSER.declareString(constructorArg(), new ParseField(CHUNKED_TEXT_FIELD));
        PARSER.declareField(constructorArg(), p -> {
            XContentBuilder b = XContentBuilder.builder(p.contentType().xContent());
            b.copyCurrentStructure(p);
            return BytesReference.bytes(b);
        }, new ParseField(CHUNKED_EMBEDDINGS_FIELD), ObjectParser.ValueType.OBJECT_ARRAY);
    }

    static LegacySemanticTextChunk parse(SemanticParserContext context, XContentParser parser) throws IOException {
        return PARSER.parse(parser, context);
    }

    private final String text;

    public LegacySemanticTextChunk(String text, BytesReference rawEmbeddings, XContentType contentType) {
        super(-1, -1, rawEmbeddings, contentType);
        this.text = Objects.requireNonNull(text);
    }

    public String text() {
        return text;
    }

    @Override
    protected void toXContentSourceValue(XContentBuilder builder, Params params) throws IOException {
        builder.field(CHUNKED_TEXT_FIELD, text);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (super.equals(o) == false) return false;
        LegacySemanticTextChunk that = (LegacySemanticTextChunk) o;
        return Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), text);
    }
}
