/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.elasticsearch.inference.ChunkedInference;
import org.elasticsearch.inference.ChunkingSettings;
import org.elasticsearch.inference.MinimalServiceSettings;
import org.elasticsearch.inference.Model;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class LegacySemanticTextFieldTestUtils {
    private LegacySemanticTextFieldTestUtils() {}

    public static void addLegacySemanticTextInferenceResults(
        XContentBuilder sourceBuilder,
        List<LegacySemanticTextField> semanticTextInferenceResults
    ) throws IOException {
        for (var field : semanticTextInferenceResults) {
            sourceBuilder.field(field.fieldName());
            sourceBuilder.value(field);
        }
    }

    public static LegacySemanticTextField randomLegacySemanticTextField(
        String fieldName,
        Model model,
        ChunkingSettings chunkingSettings,
        List<String> inputs,
        XContentType contentType
    ) throws IOException {
        ChunkedInference results = SemanticFieldTestUtils.randomChunkedInferenceEmbedding(model, inputs);
        return legacySemanticTextFieldFromChunkedInferenceResults(fieldName, model, chunkingSettings, inputs, results, contentType);
    }

    public static LegacySemanticTextField legacySemanticTextFieldFromChunkedInferenceResults(
        String fieldName,
        Model model,
        ChunkingSettings chunkingSettings,
        List<String> inputs,
        ChunkedInference results,
        XContentType contentType
    ) throws IOException {
        // In this test framework, we don't perform "real" chunking; each input generates one chunk. Thus, we can assume there is a
        // one-to-one relationship between inputs and chunks. Iterate over the inputs and chunks to match each input with its
        // corresponding chunk.
        final List<SemanticChunk> chunks = new ArrayList<>(inputs.size());
        Iterator<String> inputsIt = inputs.iterator();
        Iterator<ChunkedInference.Chunk> chunkIt = results.chunksAsByteReference(contentType.xContent());
        while (inputsIt.hasNext() && chunkIt.hasNext()) {
            String input = inputsIt.next();
            var chunk = chunkIt.next();
            chunks.add(new LegacySemanticTextChunk(input, chunk.bytesReference(), contentType));
        }

        if (inputsIt.hasNext() || chunkIt.hasNext()) {
            throw new IllegalArgumentException("Input list size and chunk count do not match");
        }

        Map<String, List<SemanticChunk>> chunkMap = Map.of(fieldName, chunks);
        return new LegacySemanticTextField(
            fieldName,
            new LegacySemanticTextInferenceResult(
                model.getInferenceEntityId(),
                new MinimalServiceSettings(model),
                chunkingSettings,
                chunkMap
            ),
            inputs
        );
    }
}
