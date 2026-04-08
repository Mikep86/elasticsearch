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
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.inference.model.TestModel;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.xpack.inference.mapper.SemanticFieldTestUtils.generateRandomChunkingSettings;
import static org.hamcrest.Matchers.equalTo;

public class LegacySemanticTextFieldTests extends SemanticFieldTests {

    @Override
    protected SemanticField createTestInstance() {
        List<String> rawValues = randomList(1, 5, () -> SemanticFieldTestUtils.randomSemanticFieldInput().toString());
        try {
            return LegacySemanticTextFieldTestUtils.randomLegacySemanticTextField(
                NAME,
                TestModel.createRandomInstance(),
                generateRandomChunkingSettings(),
                rawValues,
                randomFrom(XContentType.values())
            );
        } catch (IOException e) {
            throw new AssertionError("Failed to create random LegacySemanticTextField instance", e);
        }
    }

    @Override
    protected SemanticField doParseInstance(XContentParser parser) throws IOException {
        return LegacySemanticTextField.parse(new SemanticParserContext(true, NAME, parser.contentType()), parser);
    }

    @Override
    protected SemanticField createFieldWithModelSettings(MinimalServiceSettings modelSettings) {
        return new LegacySemanticTextField(
            NAME,
            new LegacySemanticTextInferenceResult(randomIdentifier(), modelSettings, null, Map.of()),
            List.of()
        );
    }

    @Override
    protected SemanticField createFieldFromChunkedInference(
        Model model,
        ChunkingSettings chunkingSettings,
        List<String> inputs,
        ChunkedInference results,
        XContentType contentType
    ) throws IOException {
        return LegacySemanticTextFieldTestUtils.legacySemanticTextFieldFromChunkedInferenceResults(
            NAME,
            model,
            chunkingSettings,
            inputs,
            results,
            contentType
        );
    }

    @Override
    protected void assertEqualInstances(SemanticField expectedInstance, SemanticField newInstance) {
        super.assertEqualInstances(expectedInstance, newInstance);
        LegacySemanticTextField expectedInstanceLegacy = asInstanceOf(LegacySemanticTextField.class, expectedInstance);
        LegacySemanticTextField newInstanceLegacy = asInstanceOf(LegacySemanticTextField.class, newInstance);
        assertThat(newInstanceLegacy.originalValues(), equalTo(expectedInstanceLegacy.originalValues()));
    }

    @Override
    protected void assertEqualChunks(SemanticChunk expectedChunk, SemanticChunk actualChunk, MinimalServiceSettings modelSettings) {
        super.assertEqualChunks(expectedChunk, actualChunk, modelSettings);
        LegacySemanticTextChunk expectedChunkLegacy = asInstanceOf(LegacySemanticTextChunk.class, expectedChunk);
        LegacySemanticTextChunk actualChunkLegacy = asInstanceOf(LegacySemanticTextChunk.class, actualChunk);
        assertThat(actualChunkLegacy.text(), equalTo(expectedChunkLegacy.text()));
    }
}
