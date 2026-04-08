/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.inference.ChunkedInference;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.search.diversification.DenseVectorSupplier;
import org.elasticsearch.search.vectors.VectorData;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.xpack.inference.common.chunks.SemanticTextChunkUtils.getTextEmbeddingVectorFromChunk;

public class SemanticField implements ToXContentObject, DenseVectorSupplier {
    static final String INFERENCE_FIELD = "inference";

    /**
     * Converts the provided {@link ChunkedInference} into a list of {@link SemanticChunk}.
     */
    public static List<SemanticChunk> toSemanticChunks(int offsetAdjustment, ChunkedInference results, XContentType contentType)
        throws IOException {
        List<SemanticChunk> chunks = new ArrayList<>();
        Iterator<ChunkedInference.Chunk> it = results.chunksAsByteReference(contentType.xContent());
        while (it.hasNext()) {
            chunks.add(toSemanticChunk(offsetAdjustment, it.next()));
        }
        return chunks;
    }

    /**
     * Converts the provided {@link ChunkedInference.Chunk} into a {@link SemanticChunk}.
     */
    public static SemanticChunk toSemanticChunk(int offsetAdjustment, ChunkedInference.Chunk chunk) {
        int startOffset = chunk.textOffset().start() + offsetAdjustment;
        int endOffset = chunk.textOffset().end() + offsetAdjustment;
        return new SemanticChunk(startOffset, endOffset, chunk.bytesReference(), chunk.contentType());
    }

    private static final ConstructingObjectParser<SemanticField, SemanticParserContext> PARSER = new ConstructingObjectParser<>(
        "semantic_field",
        true,
        (args, context) -> new SemanticField(context.fieldName(), (SemanticInferenceResult) args[0])
    );

    static {
        PARSER.declareObject(constructorArg(), (p, c) -> SemanticInferenceResult.parse(c, p), new ParseField(INFERENCE_FIELD));
    }

    static SemanticField parse(SemanticParserContext context, XContentParser parser) throws IOException {
        return PARSER.parse(parser, context);
    }

    private final String fieldName;
    private final SemanticInferenceResult inference;

    public SemanticField(String fieldName, SemanticInferenceResult inference) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.inference = Objects.requireNonNull(inference);
        validateInference(inference);
    }

    public String fieldName() {
        return fieldName;
    }

    public SemanticInferenceResult inference() {
        return inference;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        toXContentInference(builder, params);
        builder.endObject();
        return builder;
    }

    @Override
    public String getSupplierContentType() {
        return SemanticTextFieldMapper.CONTENT_TYPE;
    }

    @Override
    public List<VectorData> getDenseVectorData() throws IOException {
        if (inference.modelSettings() == null || inference.modelSettings().taskType() != TaskType.TEXT_EMBEDDING) {
            return null;
        }

        DenseVectorFieldMapper.ElementType elementType = this.inference().modelSettings().elementType();
        if (elementType == null) {
            return null;
        }

        List<VectorData> chunkVectors = new ArrayList<>();
        for (List<SemanticChunk> fieldChunks : inference.chunks().values()) {
            for (SemanticChunk chunk : fieldChunks) {
                chunkVectors.add(getTextEmbeddingVectorFromChunk(chunk, elementType));
            }
        }

        return chunkVectors;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SemanticField that = (SemanticField) o;
        return Objects.equals(fieldName, that.fieldName) && Objects.equals(inference, that.inference);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, inference);
    }

    protected void validateInference(SemanticInferenceResult inference) {
        if (inference instanceof LegacySemanticTextInferenceResult) {
            throw new IllegalStateException("Inference cannot use legacy format");
        }
    }

    protected void toXContentInference(XContentBuilder builder, Params params) throws IOException {
        builder.field(INFERENCE_FIELD, inference);
    }
}
