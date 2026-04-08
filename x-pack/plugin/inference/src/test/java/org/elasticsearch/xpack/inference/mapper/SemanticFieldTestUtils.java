/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.elasticsearch.index.mapper.InferenceMetadataFieldsMapper;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapperTestUtils;
import org.elasticsearch.inference.ChunkedInference;
import org.elasticsearch.inference.ChunkingSettings;
import org.elasticsearch.inference.MinimalServiceSettings;
import org.elasticsearch.inference.Model;
import org.elasticsearch.inference.WeightedToken;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.inference.results.ChunkedInferenceEmbedding;
import org.elasticsearch.xpack.core.inference.results.DenseEmbeddingByteResults;
import org.elasticsearch.xpack.core.inference.results.DenseEmbeddingFloatResults;
import org.elasticsearch.xpack.core.inference.results.EmbeddingResults;
import org.elasticsearch.xpack.core.inference.results.SparseEmbeddingResults;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.test.ESTestCase.randomByte;
import static org.elasticsearch.test.ESTestCase.randomFloat;
import static org.elasticsearch.test.ESTestCase.randomIntBetween;

public class SemanticFieldTestUtils {
    private SemanticFieldTestUtils() {}

    public static void addSemanticInferenceResults(XContentBuilder sourceBuilder, List<SemanticField> semanticInferenceResults)
        throws IOException {
        // Use a linked hash map to maintain insertion-order iteration over the inference fields
        Map<String, Object> inferenceMetadataFields = new LinkedHashMap<>();
        for (var field : semanticInferenceResults) {
            inferenceMetadataFields.put(field.fieldName(), field);
        }
        sourceBuilder.field(InferenceMetadataFieldsMapper.NAME, inferenceMetadataFields);
    }

    public static SemanticField randomSemanticField(
        String fieldName,
        Model model,
        ChunkingSettings chunkingSettings,
        List<String> inputs,
        XContentType contentType
    ) throws IOException {
        ChunkedInference results = randomChunkedInferenceEmbedding(model, inputs);
        return semanticFieldFromChunkedInferenceResults(fieldName, model, chunkingSettings, inputs, results, contentType);
    }

    public static SemanticField semanticFieldFromChunkedInferenceResults(
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
        int offsetAdjustment = 0;
        Iterator<String> inputsIt = inputs.iterator();
        Iterator<ChunkedInference.Chunk> chunkIt = results.chunksAsByteReference(contentType.xContent());
        while (inputsIt.hasNext() && chunkIt.hasNext()) {
            String input = inputsIt.next();
            var chunk = chunkIt.next();
            chunks.add(SemanticField.toSemanticChunk(offsetAdjustment, chunk));

            // When using the inference metadata fields format, all the input values are concatenated so that the
            // chunk text offsets are expressed in the context of a single string. Calculate the offset adjustment
            // to apply to account for this.
            offsetAdjustment += input.length() + 1; // Add one for separator char length
        }

        if (inputsIt.hasNext() || chunkIt.hasNext()) {
            throw new IllegalArgumentException("Input list size and chunk count do not match");
        }

        return new SemanticField(
            fieldName,
            new SemanticInferenceResult(
                model.getInferenceEntityId(),
                new MinimalServiceSettings(model),
                chunkingSettings,
                Map.of(fieldName, chunks)
            )
        );
    }

    public static ChunkedInferenceEmbedding randomChunkedInferenceEmbedding(Model model, List<String> inputs) {
        return switch (model.getTaskType()) {
            case SPARSE_EMBEDDING -> randomChunkedInferenceEmbeddingSparse(inputs);
            case TEXT_EMBEDDING -> switch (model.getServiceSettings().elementType()) {
                case FLOAT, BFLOAT16 -> randomChunkedInferenceEmbeddingFloat(model, inputs);
                case BIT, BYTE -> randomChunkedInferenceEmbeddingByte(model, inputs);
            };
            default -> throw new AssertionError("invalid task type: " + model.getTaskType().name());
        };
    }

    public static ChunkedInferenceEmbedding randomChunkedInferenceEmbeddingSparse(List<String> inputs) {
        return randomChunkedInferenceEmbeddingSparse(inputs, true);
    }

    public static ChunkedInferenceEmbedding randomChunkedInferenceEmbeddingSparse(List<String> inputs, boolean withFloats) {
        List<EmbeddingResults.Chunk> chunks = new ArrayList<>();
        for (String input : inputs) {
            var tokens = new ArrayList<WeightedToken>();
            for (var token : input.split("\\s+")) {
                tokens.add(new WeightedToken(token, withFloats ? Math.max(Float.MIN_NORMAL, randomFloat()) : randomIntBetween(1, 255)));
            }
            chunks.add(
                new EmbeddingResults.Chunk(
                    new SparseEmbeddingResults.Embedding(tokens, false),
                    new ChunkedInference.TextOffset(0, input.length())
                )
            );
        }
        return new ChunkedInferenceEmbedding(chunks);
    }

    public static ChunkedInferenceEmbedding randomChunkedInferenceEmbeddingFloat(Model model, List<String> inputs) {
        DenseVectorFieldMapper.ElementType elementType = model.getServiceSettings().elementType();
        int embeddingLength = DenseVectorFieldMapperTestUtils.getEmbeddingLength(elementType, model.getServiceSettings().dimensions());
        assert elementType == DenseVectorFieldMapper.ElementType.FLOAT || elementType == DenseVectorFieldMapper.ElementType.BFLOAT16;

        List<EmbeddingResults.Chunk> chunks = new ArrayList<>();
        for (String input : inputs) {
            float[] values = randomFloatVectorOfLength(embeddingLength);
            chunks.add(
                new EmbeddingResults.Chunk(
                    new DenseEmbeddingFloatResults.Embedding(values),
                    new ChunkedInference.TextOffset(0, input.length())
                )
            );
        }
        return new ChunkedInferenceEmbedding(chunks);
    }

    public static float[] randomFloatVectorOfLength(int embeddingLength) {
        float[] values = new float[embeddingLength];
        for (int j = 0; j < values.length; j++) {
            // to avoid vectors with zero magnitude
            values[j] = Math.max(1e-6f, randomFloat());
        }
        return values;
    }

    public static ChunkedInferenceEmbedding randomChunkedInferenceEmbeddingByte(Model model, List<String> inputs) {
        DenseVectorFieldMapper.ElementType elementType = model.getServiceSettings().elementType();
        int embeddingLength = DenseVectorFieldMapperTestUtils.getEmbeddingLength(elementType, model.getServiceSettings().dimensions());
        assert elementType == DenseVectorFieldMapper.ElementType.BYTE || elementType == DenseVectorFieldMapper.ElementType.BIT;

        List<EmbeddingResults.Chunk> chunks = new ArrayList<>();
        for (String input : inputs) {
            byte[] values = randomByteVectorOfLength(embeddingLength);
            chunks.add(
                new EmbeddingResults.Chunk(
                    new DenseEmbeddingByteResults.Embedding(values),
                    new ChunkedInference.TextOffset(0, input.length())
                )
            );
        }
        return new ChunkedInferenceEmbedding(chunks);
    }

    public static byte[] randomByteVectorOfLength(int embeddingLength) {
        byte[] values = new byte[embeddingLength];
        for (int j = 0; j < values.length; j++) {
            // to avoid vectors with zero magnitude
            values[j] = (byte) Math.max(1, randomByte());
        }
        return values;
    }
}
