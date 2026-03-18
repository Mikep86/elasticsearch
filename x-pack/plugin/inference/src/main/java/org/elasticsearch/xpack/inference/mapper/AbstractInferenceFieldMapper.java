/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.elasticsearch.index.mapper.DocumentParserContext;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.IndexType;
import org.elasticsearch.index.mapper.InferenceFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.ObjectMapper;
import org.elasticsearch.index.mapper.SimpleMappedFieldType;
import org.elasticsearch.index.mapper.vectors.VectorsFormatProvider;
import org.elasticsearch.inference.MinimalServiceSettings;
import org.elasticsearch.xpack.inference.registry.ModelRegistry;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public abstract class AbstractInferenceFieldMapper extends FieldMapper implements InferenceFieldMapper {
    protected final ModelRegistry modelRegistry;
    protected final List<VectorsFormatProvider> vectorsFormatProviders;

    AbstractInferenceFieldMapper(
        String simpleName,
        MappedFieldType mappedFieldType,
        BuilderParams builderParams,
        ModelRegistry modelRegistry,
        List<VectorsFormatProvider> vectorsFormatProviders
    ) {
        super(simpleName, mappedFieldType, builderParams);
        this.modelRegistry = modelRegistry;
        this.vectorsFormatProviders = vectorsFormatProviders;
    }

    abstract void parseInferenceField(DocumentParserContext context) throws IOException;

    public abstract static class AbstractInferenceFieldType extends SimpleMappedFieldType {
        private final String inferenceId;
        private final String searchInferenceId;
        private final MinimalServiceSettings modelSettings;
        private final ObjectMapper inferenceField;

        public AbstractInferenceFieldType(
            String name,
            String inferenceId,
            String searchInferenceId,
            MinimalServiceSettings modelSettings,
            ObjectMapper inferenceField,
            Map<String, String> meta
        ) {
            super(name, IndexType.terms(true, false), false, meta);
            this.inferenceId = inferenceId;
            this.searchInferenceId = searchInferenceId;
            this.modelSettings = modelSettings;
            this.inferenceField = inferenceField;
        }

        public String getInferenceId() {
            return inferenceId;
        }

        public String getSearchInferenceId() {
            return searchInferenceId;
        }

        public MinimalServiceSettings getModelSettings() {
            return modelSettings;
        }

        public ObjectMapper getInferenceField() {
            return inferenceField;
        }
    }
}
