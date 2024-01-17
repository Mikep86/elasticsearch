/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.search.Query;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.query.SearchExecutionContext;

import java.io.IOException;
import java.util.Map;

public class MockInferenceModelFieldMapper extends FieldMapper {
    public static final String CONTENT_TYPE = "mock_inference_model";

    public static final TypeParser PARSER = new TypeParser((n, c) -> new Builder(n));

    private static MockInferenceModelFieldMapper toType(FieldMapper in) {
        return (MockInferenceModelFieldMapper) in;
    }

    private MockInferenceModelFieldMapper(String simpleName, MappedFieldType mappedFieldType, MultiFields multiFields, CopyTo copyTo) {
        super(simpleName, mappedFieldType, multiFields, copyTo);
    }

    @Override
    protected void parseCreateField(DocumentParserContext context) throws IOException {
        context.parser().textOrNull();
    }

    @Override
    public FieldMapper.Builder getMergeBuilder() {
        return new Builder(simpleName()).init(this);
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public MockInferenceModelFieldType fieldType() {
        return (MockInferenceModelFieldType) super.fieldType();
    }

    public static class MockInferenceModelFieldType extends SimpleMappedFieldType implements InferenceModelFieldType {
        private final String modelId;

        public MockInferenceModelFieldType(String name, String modelId) {
            super(name, false, false, false, TextSearchInfo.NONE, Map.of());
            this.modelId = modelId;
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public Query termQuery(Object value, SearchExecutionContext context) {
            throw new IllegalArgumentException("termQuery not implemented");
        }

        @Override
        public ValueFetcher valueFetcher(SearchExecutionContext context, String format) {
            return SourceValueFetcher.toString(name(), context, format);
        }

        @Override
        public String getInferenceModel() {
            return modelId;
        }
    }

    public static class Builder extends FieldMapper.Builder {
        private final Parameter<String> modelId = Parameter.stringParam("model_id", false, m -> toType(m).fieldType().modelId, null)
            .addValidator(v -> {
                if (Strings.isEmpty(v)) {
                    throw new IllegalArgumentException("field [model_id] must be specified");
                }
            });

        public Builder(String name) {
            super(name);
        }

        @Override
        protected Parameter<?>[] getParameters() {
            return new Parameter<?>[] {modelId};
        }

        @Override
        public FieldMapper build(MapperBuilderContext context) {
            return new MockInferenceModelFieldMapper(
                name(),
                new MockInferenceModelFieldType(name(), modelId.getValue()),
                multiFieldsBuilder.build(this, context),
                copyTo
            );
        }
    }
}
