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
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

// TODO: Add input_index

public class SemanticChunk implements ToXContentObject {
    static final String CHUNKED_START_OFFSET_FIELD = "start_offset";
    static final String CHUNKED_END_OFFSET_FIELD = "end_offset";
    static final String CHUNKED_EMBEDDINGS_FIELD = "embeddings";

    private static final ConstructingObjectParser<SemanticChunk, SemanticParserContext> PARSER = new ConstructingObjectParser<>(
        "semantic_chunk",
        true,
        (args, context) -> new SemanticChunk((int) args[0], (int) args[1], (BytesReference) args[2], context.xContentType())
    );

    static {
        PARSER.declareInt(constructorArg(), new ParseField(CHUNKED_START_OFFSET_FIELD));
        PARSER.declareInt(constructorArg(), new ParseField(CHUNKED_END_OFFSET_FIELD));
        PARSER.declareField(constructorArg(), p -> {
            XContentBuilder b = XContentBuilder.builder(p.contentType().xContent());
            b.copyCurrentStructure(p);
            return BytesReference.bytes(b);
        }, new ParseField(CHUNKED_EMBEDDINGS_FIELD), ObjectParser.ValueType.OBJECT_ARRAY);
    }

    static SemanticChunk parse(SemanticParserContext context, XContentParser parser) throws IOException {
        return PARSER.parse(parser, context);
    }

    private final int startOffset;
    private final int endOffset;
    private final BytesReference rawEmbeddings;
    private final XContentType contentType;

    public SemanticChunk(int startOffset, int endOffset, BytesReference rawEmbeddings, XContentType contentType) {
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.rawEmbeddings = Objects.requireNonNull(rawEmbeddings);
        this.contentType = Objects.requireNonNull(contentType);
    }

    public int startOffset() {
        return startOffset;
    }

    public int endOffset() {
        return endOffset;
    }

    public BytesReference rawEmbeddings() {
        return rawEmbeddings;
    }

    public XContentType contentType() {
        return contentType;
    }

    protected void toXContentSourceValue(XContentBuilder builder, Params params) throws IOException {
        builder.field(CHUNKED_START_OFFSET_FIELD, startOffset);
        builder.field(CHUNKED_END_OFFSET_FIELD, endOffset);
    }

    protected void toXContentEmbeddings(XContentBuilder builder, Params params) throws IOException {
        builder.field(CHUNKED_EMBEDDINGS_FIELD, rawEmbeddings);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        toXContentSourceValue(builder, params);
        toXContentEmbeddings(builder, params);
        builder.endObject();

        return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SemanticChunk that = (SemanticChunk) o;
        return startOffset == that.startOffset
            && endOffset == that.endOffset
            && Objects.equals(rawEmbeddings, that.rawEmbeddings)
            && contentType == that.contentType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(startOffset, endOffset, rawEmbeddings, contentType);
    }
}
