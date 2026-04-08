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
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xpack.core.inference.chunking.ChunkingSettingsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.xcontent.ConstructingObjectParser.optionalConstructorArg;

public class LegacySemanticTextInferenceResult extends SemanticInferenceResult {

    @SuppressWarnings("unchecked")
    private static final ConstructingObjectParser<LegacySemanticTextInferenceResult, SemanticParserContext> PARSER =
        new ConstructingObjectParser<>("legacy_semantic_text_inference_result", true, (args, context) -> {
            String inferenceId = (String) args[0];
            MinimalServiceSettings modelSettings = (MinimalServiceSettings) args[1];
            Map<String, Object> chunkingSettings = (Map<String, Object>) args[2];
            List<SemanticChunk> chunks = (List<SemanticChunk>) args[3];
            return new LegacySemanticTextInferenceResult(
                inferenceId,
                modelSettings,
                ChunkingSettingsBuilder.fromMap(chunkingSettings, false),
                Map.of(context.fieldName(), chunks)
            );
        });

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
            LegacySemanticTextInferenceResult::parseChunksArray,
            new ParseField(CHUNKS_FIELD),
            ObjectParser.ValueType.OBJECT_ARRAY
        );
    }

    static LegacySemanticTextInferenceResult parse(SemanticParserContext context, XContentParser parser) throws IOException {
        return PARSER.parse(parser, context);
    }

    private static List<SemanticChunk> parseChunksArray(XContentParser parser, SemanticParserContext context) throws IOException {
        List<SemanticChunk> results = new ArrayList<>();
        XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_ARRAY, parser.currentToken(), parser);
        while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
            results.add(LegacySemanticTextChunk.parse(context, parser));
        }
        return results;
    }

    public LegacySemanticTextInferenceResult(
        String inferenceId,
        @Nullable MinimalServiceSettings modelSettings,
        @Nullable ChunkingSettings chunkingSettings,
        Map<String, List<SemanticChunk>> chunks
    ) {
        super(inferenceId, modelSettings, chunkingSettings, chunks);
    }

    @Override
    protected void validateChunks(Map<String, List<SemanticChunk>> chunks) {
        for (var entry : chunks.entrySet()) {
            for (SemanticChunk chunk : entry.getValue()) {
                if (chunk instanceof LegacySemanticTextChunk == false) {
                    throw new IllegalStateException("Chunk map must only contain legacy semantic text chunks");
                }
            }
        }
    }

    @Override
    protected void toXContentChunks(XContentBuilder builder, Params params) throws IOException {
        builder.startArray(CHUNKS_FIELD);
        for (var entry : chunks().entrySet()) {
            for (SemanticChunk chunk : entry.getValue()) {
                chunk.toXContent(builder, params);
            }
        }
        builder.endArray();
    }
}
