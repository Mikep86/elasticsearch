/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.elasticsearch.common.xcontent.XContentParserUtils;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.inference.ChunkingSettings;
import org.elasticsearch.inference.MinimalServiceSettings;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xpack.core.inference.chunking.ChunkingSettingsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.xcontent.ConstructingObjectParser.optionalConstructorArg;

public class SemanticInferenceResult implements ToXContentObject {
    static final String INFERENCE_ID_FIELD = "inference_id";
    static final String MODEL_SETTINGS_FIELD = "model_settings";
    static final String CHUNKING_SETTINGS_FIELD = "chunking_settings";
    static final String CHUNKS_FIELD = "chunks";

    @SuppressWarnings("unchecked")
    private static final ConstructingObjectParser<SemanticInferenceResult, SemanticParserContext> PARSER = new ConstructingObjectParser<>(
        "semantic_inference_result",
        true,
        args -> {
            String inferenceId = (String) args[0];
            MinimalServiceSettings modelSettings = (MinimalServiceSettings) args[1];
            Map<String, Object> chunkingSettings = (Map<String, Object>) args[2];
            Map<String, List<SemanticChunk>> chunks = (Map<String, List<SemanticChunk>>) args[3];
            return new SemanticInferenceResult(
                inferenceId,
                modelSettings,
                ChunkingSettingsBuilder.fromMap(chunkingSettings, false),
                chunks
            );
        }
    );

    static {
        PARSER.declareString(constructorArg(), new ParseField(INFERENCE_ID_FIELD));
        PARSER.declareObjectOrNull(
            optionalConstructorArg(),
            (p, c) -> MinimalServiceSettings.parse(p),
            null,
            new ParseField(MODEL_SETTINGS_FIELD)
        );
        PARSER.declareObjectOrNull(optionalConstructorArg(), (p, c) -> p.map(), null, new ParseField(CHUNKING_SETTINGS_FIELD));
        PARSER.declareField(
            constructorArg(),
            SemanticInferenceResult::parseChunksMap,
            new ParseField(CHUNKS_FIELD),
            ObjectParser.ValueType.OBJECT
        );
    }

    static SemanticInferenceResult parse(SemanticParserContext context, XContentParser parser) throws IOException {
        return PARSER.parse(parser, context);
    }

    private static Map<String, List<SemanticChunk>> parseChunksMap(XContentParser parser, SemanticParserContext context)
        throws IOException {
        Map<String, List<SemanticChunk>> resultMap = new LinkedHashMap<>();
        XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.currentToken(), parser);
        while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
            XContentParserUtils.ensureExpectedToken(XContentParser.Token.FIELD_NAME, parser.currentToken(), parser);
            String fieldName = parser.currentName();
            XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_ARRAY, parser.nextToken(), parser);
            var chunks = resultMap.computeIfAbsent(fieldName, k -> new ArrayList<>());
            while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                chunks.add(SemanticChunk.parse(context, parser));
            }
        }
        return resultMap;
    }

    private final String inferenceId;
    private final MinimalServiceSettings modelSettings;
    private final ChunkingSettings chunkingSettings;
    private final Map<String, List<SemanticChunk>> chunks;

    public SemanticInferenceResult(
        String inferenceId,
        @Nullable MinimalServiceSettings modelSettings,
        @Nullable ChunkingSettings chunkingSettings,
        Map<String, List<SemanticChunk>> chunks
    ) {
        this.inferenceId = Objects.requireNonNull(inferenceId);
        this.modelSettings = modelSettings;
        this.chunkingSettings = chunkingSettings;
        this.chunks = Objects.requireNonNull(chunks);
        validateChunks(chunks);
    }

    public String inferenceId() {
        return inferenceId;
    }

    public MinimalServiceSettings modelSettings() {
        return modelSettings;
    }

    public ChunkingSettings chunkingSettings() {
        return chunkingSettings;
    }

    public Map<String, List<SemanticChunk>> chunks() {
        return chunks;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(INFERENCE_ID_FIELD, inferenceId);
        builder.field(MODEL_SETTINGS_FIELD, modelSettings != null ? modelSettings.getFilteredXContentObject() : null);
        if (chunkingSettings != null) {
            builder.field(CHUNKING_SETTINGS_FIELD, chunkingSettings);
        }
        toXContentChunks(builder, params);
        builder.endObject();
        return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SemanticInferenceResult that = (SemanticInferenceResult) o;
        return Objects.equals(inferenceId, that.inferenceId)
            && Objects.equals(modelSettings, that.modelSettings)
            && Objects.equals(chunkingSettings, that.chunkingSettings)
            && Objects.equals(chunks, that.chunks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inferenceId, modelSettings, chunkingSettings, chunks);
    }

    protected void validateChunks(Map<String, List<SemanticChunk>> chunks) {
        for (var entry : chunks.entrySet()) {
            for (SemanticChunk chunk : entry.getValue()) {
                if (chunk instanceof LegacySemanticTextChunk) {
                    throw new IllegalStateException("Chunk map cannot contain legacy semantic text chunks");
                }
            }
        }
    }

    protected void toXContentChunks(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(CHUNKS_FIELD);
        for (var entry : chunks.entrySet()) {
            builder.startArray(entry.getKey());
            for (SemanticChunk chunk : entry.getValue()) {
                chunk.toXContent(builder, params);
            }
            builder.endArray();
        }
        builder.endObject();
    }
}
