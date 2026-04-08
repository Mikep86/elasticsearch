/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapperTestUtils;
import org.elasticsearch.inference.ChunkedInference;
import org.elasticsearch.inference.ChunkingSettings;
import org.elasticsearch.inference.EndpointMetadataTests;
import org.elasticsearch.inference.MinimalServiceSettings;
import org.elasticsearch.inference.Model;
import org.elasticsearch.inference.SimilarityMeasure;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.inference.WeightedToken;
import org.elasticsearch.inference.metadata.EndpointMetadata;
import org.elasticsearch.search.vectors.VectorData;
import org.elasticsearch.test.AbstractXContentTestCase;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.inference.results.EmbeddingByteResults;
import org.elasticsearch.xpack.core.inference.results.EmbeddingFloatResults;
import org.elasticsearch.xpack.core.inference.results.EmbeddingResults;
import org.elasticsearch.xpack.inference.model.TestModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.elasticsearch.xpack.inference.mapper.SemanticFieldTestUtils.generateRandomChunkingSettings;
import static org.elasticsearch.xpack.inference.mapper.SemanticFieldTestUtils.randomSemanticField;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class SemanticFieldTests extends AbstractXContentTestCase<SemanticField> {
    protected static final String NAME = "field";

    @Override
    protected Predicate<String> getRandomFieldsExcludeFilter() {
        return n -> n.endsWith(SemanticChunk.CHUNKED_EMBEDDINGS_FIELD);
    }

    @Override
    protected void assertEqualInstances(SemanticField expectedInstance, SemanticField newInstance) {
        assertThat(newInstance.fieldName(), equalTo(expectedInstance.fieldName()));
        assertThat(newInstance.inference().inferenceId(), equalTo(expectedInstance.inference().inferenceId()));
        assertThat(newInstance.inference().modelSettings(), equalTo(expectedInstance.inference().modelSettings()));
        assertThat(newInstance.inference().chunkingSettings(), equalTo(expectedInstance.inference().chunkingSettings()));
        assertThat(newInstance.inference().chunks().size(), equalTo(expectedInstance.inference().chunks().size()));
        MinimalServiceSettings modelSettings = newInstance.inference().modelSettings();
        for (var entry : newInstance.inference().chunks().entrySet()) {
            var expectedChunks = expectedInstance.inference().chunks().get(entry.getKey());
            assertNotNull(expectedChunks);
            assertThat(entry.getValue().size(), equalTo(expectedChunks.size()));
            for (int i = 0; i < entry.getValue().size(); i++) {
                var actualChunk = entry.getValue().get(i);
                var expectedChunk = expectedChunks.get(i);
                assertEqualChunks(expectedChunk, actualChunk, modelSettings);
            }
        }
    }

    protected void assertEqualChunks(SemanticChunk expectedChunk, SemanticChunk actualChunk, MinimalServiceSettings modelSettings) {
        assertThat(actualChunk.startOffset(), equalTo(expectedChunk.startOffset()));
        assertThat(actualChunk.endOffset(), equalTo(expectedChunk.endOffset()));
        switch (modelSettings.taskType()) {
            case TEXT_EMBEDDING -> {
                int embeddingLength = DenseVectorFieldMapperTestUtils.getEmbeddingLength(
                    modelSettings.elementType(),
                    modelSettings.dimensions()
                );
                double[] expectedVector = parseDenseVector(expectedChunk.rawEmbeddings(), embeddingLength, expectedChunk.contentType());
                double[] newVector = parseDenseVector(actualChunk.rawEmbeddings(), embeddingLength, actualChunk.contentType());
                assertArrayEquals(expectedVector, newVector, 0.0000001f);
            }
            case SPARSE_EMBEDDING -> {
                List<WeightedToken> expectedTokens = parseWeightedTokens(expectedChunk.rawEmbeddings(), expectedChunk.contentType());
                List<WeightedToken> newTokens = parseWeightedTokens(actualChunk.rawEmbeddings(), actualChunk.contentType());
                assertThat(newTokens, equalTo(expectedTokens));
            }
            default -> throw new AssertionError("Invalid task type " + modelSettings.taskType());
        }
    }

    @Override
    protected SemanticField createTestInstance() {
        List<String> rawValues = randomList(1, 5, () -> SemanticFieldTestUtils.randomSemanticFieldInput().toString());
        try {
            return randomSemanticField(
                NAME,
                TestModel.createRandomInstance(),
                generateRandomChunkingSettings(),
                rawValues,
                randomFrom(XContentType.values())
            );
        } catch (IOException e) {
            throw new AssertionError("Failed to create random SemanticField instance", e);
        }
    }

    @Override
    protected SemanticField doParseInstance(XContentParser parser) throws IOException {
        return SemanticField.parse(new SemanticParserContext(false, NAME, parser.contentType()), parser);
    }

    @Override
    protected boolean supportsUnknownFields() {
        return false;
    }

    protected SemanticField createFieldWithModelSettings(MinimalServiceSettings modelSettings) {
        return new SemanticField(NAME, new SemanticInferenceResult(randomIdentifier(), modelSettings, null, Map.of()));
    }

    protected SemanticField createFieldFromChunkedInference(
        Model model,
        ChunkingSettings chunkingSettings,
        List<String> inputs,
        ChunkedInference results,
        XContentType contentType
    ) throws IOException {
        return SemanticFieldTestUtils.semanticFieldFromChunkedInferenceResults(NAME, model, chunkingSettings, inputs, results, contentType);
    }

    public void testModelSettingsValidation() {
        NullPointerException npe = expectThrows(NullPointerException.class, () -> {
            new MinimalServiceSettings("service", null, 10, SimilarityMeasure.COSINE, DenseVectorFieldMapper.ElementType.FLOAT);
        });
        assertThat(npe.getMessage(), equalTo("task type must not be null"));

        IllegalArgumentException ex = expectThrows(IllegalArgumentException.class, () -> {
            new MinimalServiceSettings("service", TaskType.SPARSE_EMBEDDING, 10, null, null);
        });
        assertThat(ex.getMessage(), containsString("[dimensions] is not allowed"));

        ex = expectThrows(IllegalArgumentException.class, () -> {
            new MinimalServiceSettings("service", TaskType.SPARSE_EMBEDDING, null, SimilarityMeasure.COSINE, null);
        });
        assertThat(ex.getMessage(), containsString("[similarity] is not allowed"));

        ex = expectThrows(IllegalArgumentException.class, () -> {
            new MinimalServiceSettings("service", TaskType.SPARSE_EMBEDDING, null, null, DenseVectorFieldMapper.ElementType.FLOAT);
        });
        assertThat(ex.getMessage(), containsString("[element_type] is not allowed"));

        ex = expectThrows(IllegalArgumentException.class, () -> {
            new MinimalServiceSettings(
                "service",
                TaskType.TEXT_EMBEDDING,
                null,
                SimilarityMeasure.COSINE,
                DenseVectorFieldMapper.ElementType.FLOAT
            );
        });
        assertThat(ex.getMessage(), containsString("required [dimensions] field is missing"));

        ex = expectThrows(IllegalArgumentException.class, () -> {
            new MinimalServiceSettings("service", TaskType.TEXT_EMBEDDING, 10, null, DenseVectorFieldMapper.ElementType.FLOAT);
        });
        assertThat(ex.getMessage(), containsString("required [similarity] field is missing"));

        ex = expectThrows(IllegalArgumentException.class, () -> {
            new MinimalServiceSettings("service", TaskType.TEXT_EMBEDDING, 10, SimilarityMeasure.COSINE, null);
        });
        assertThat(ex.getMessage(), containsString("required [element_type] field is missing"));
    }

    public void testModelSettingsXContentExcludesEndpointMetadata() throws IOException {
        final EndpointMetadata endpointMetadata = EndpointMetadataTests.randomNonEmptyInstance();
        final MinimalServiceSettings modelSettings = new MinimalServiceSettings(
            "test-service",
            TaskType.SPARSE_EMBEDDING,
            null,
            null,
            null,
            endpointMetadata
        );
        final SemanticField semanticField = createFieldWithModelSettings(modelSettings);

        XContentBuilder builder = XContentFactory.jsonBuilder();
        semanticField.toXContent(builder, ToXContent.EMPTY_PARAMS);
        String json = Strings.toString(builder);
        assertThat(json, not(containsString(EndpointMetadata.METADATA_FIELD_NAME)));

        XContentParser parser = createParser(XContentType.JSON.xContent(), json);
        SemanticField parsed = parseInstance(parser);
        assertThat(parsed.inference().modelSettings().endpointMetadata(), equalTo(EndpointMetadata.EMPTY_INSTANCE));
    }

    public void testGetDenseVectorsAsSupplier() throws IOException {
        for (int i = 0; i < 10; i++) {
            Model model = TestModel.createRandomInstance(TaskType.TEXT_EMBEDDING);
            List<String> inputs = randomList(3, 8, () -> SemanticFieldTestUtils.randomSemanticFieldInput().toString());
            var inferenceResults = SemanticFieldTestUtils.randomChunkedInferenceEmbedding(model, inputs);

            List<VectorData> chunkVectors = new ArrayList<>();
            for (EmbeddingResults.Chunk chunk : inferenceResults.chunks()) {
                VectorData thisVector = switch (chunk.embedding()) {
                    case EmbeddingFloatResults.Embedding efr -> new VectorData(efr.values());
                    case EmbeddingByteResults.Embedding ebr -> new VectorData(ebr.values());
                    default -> throw new IllegalStateException("Unexpected value: " + chunk.embedding().getClass());
                };
                chunkVectors.add(thisVector);
            }

            var field = createFieldFromChunkedInference(
                model,
                generateRandomChunkingSettings(),
                inputs,
                inferenceResults,
                randomFrom(XContentType.values())
            );

            var vectors = field.getDenseVectorData();
            assertNotNull(vectors);
            assertEquals(chunkVectors, vectors);
        }
    }

    private static double[] parseDenseVector(BytesReference value, int numDims, XContentType contentType) {
        try (XContentParser parser = XContentHelper.createParserNotCompressed(XContentParserConfiguration.EMPTY, value, contentType)) {
            parser.nextToken();
            assertThat(parser.currentToken(), equalTo(XContentParser.Token.START_ARRAY));
            double[] values = new double[numDims];
            for (int i = 0; i < numDims; i++) {
                assertThat(parser.nextToken(), equalTo(XContentParser.Token.VALUE_NUMBER));
                values[i] = parser.doubleValue();
            }
            assertThat(parser.nextToken(), equalTo(XContentParser.Token.END_ARRAY));
            return values;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<WeightedToken> parseWeightedTokens(BytesReference value, XContentType contentType) {
        try (XContentParser parser = XContentHelper.createParserNotCompressed(XContentParserConfiguration.EMPTY, value, contentType)) {
            Map<String, Object> map = parser.map();
            List<WeightedToken> weightedTokens = new ArrayList<>();
            for (var entry : map.entrySet()) {
                weightedTokens.add(new WeightedToken(entry.getKey(), ((Number) entry.getValue()).floatValue()));
            }
            return weightedTokens;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
