/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.mapper;

import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.cluster.metadata.InferenceFieldMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.fielddata.FieldDataContext;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.mapper.BlockLoader;
import org.elasticsearch.index.mapper.DocumentParserContext;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.IndexType;
import org.elasticsearch.index.mapper.InferenceFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperBuilderContext;
import org.elasticsearch.index.mapper.NestedObjectMapper;
import org.elasticsearch.index.mapper.ObjectMapper;
import org.elasticsearch.index.mapper.SimpleMappedFieldType;
import org.elasticsearch.index.mapper.ValueFetcher;
import org.elasticsearch.index.mapper.vectors.VectorsFormatProvider;
import org.elasticsearch.index.query.NestedQueryBuilder;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.inference.ChunkingSettings;
import org.elasticsearch.inference.MinimalServiceSettings;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xpack.inference.registry.ModelRegistry;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.CHUNKED_EMBEDDINGS_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.CHUNKED_OFFSET_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.CHUNKING_SETTINGS_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.CHUNKS_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.INFERENCE_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.INFERENCE_ID_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.MODEL_SETTINGS_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.SEARCH_INFERENCE_ID_FIELD;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.getChunksFieldName;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.getEmbeddingsFieldName;

public class SemanticFieldMapper extends FieldMapper implements InferenceFieldMapper {
    public static final String CONTENT_TYPE = "semantic";
    public static final float DEFAULT_RESCORE_OVERSAMPLE = 3.0f;

    static final String INDEX_OPTIONS_FIELD = "index_options";

    public static class Builder extends FieldMapper.Builder {
        protected final ModelRegistry modelRegistry;
        protected final IndexSettings indexSettings;
        protected final IndexVersion indexVersionCreated;
        protected final boolean experimentalFeaturesEnabled;
        protected final List<VectorsFormatProvider> vectorsFormatProviders;

        protected final Parameter<String> inferenceId;
        protected final Parameter<String> searchInferenceId;
        protected final Parameter<MinimalServiceSettings> modelSettings;
        protected final Parameter<SemanticTextIndexOptions> indexOptions;
        protected final Parameter<ChunkingSettings> chunkingSettings;
        protected final Parameter<Map<String, String>> meta;

        public Builder(
            String name,
            Function<Query, BitSetProducer> bitSetProducer,
            IndexSettings indexSettings,
            ModelRegistry modelRegistry,
            List<VectorsFormatProvider> vectorsFormatProviders
        ) {
            super(name);
            this.modelRegistry = modelRegistry;
            this.indexSettings = indexSettings;
            this.indexVersionCreated = indexSettings.getIndexVersionCreated();
            this.experimentalFeaturesEnabled = IndexSettings.DENSE_VECTOR_EXPERIMENTAL_FEATURES_SETTING.get(indexSettings.getSettings());
            this.vectorsFormatProviders = vectorsFormatProviders;

            this.inferenceId = configureInferenceIdParam();
            this.searchInferenceId = configureSearchInferenceIdParam();
            this.modelSettings = configureModelSettingsParam();
            this.indexOptions = configureIndexOptionsParam();
            this.chunkingSettings = configureChunkingSettingsParam();
            this.meta = configureMetaParam();
        }

        public Builder setInferenceId(String id) {
            this.inferenceId.setValue(id);
            return this;
        }

        public Builder setModelSettings(MinimalServiceSettings value) {
            this.modelSettings.setValue(value);
            return this;
        }

        public Builder setChunkingSettings(ChunkingSettings value) {
            this.chunkingSettings.setValue(value);
            return this;
        }

        protected Parameter<String> configureInferenceIdParam() {
            return Parameter.stringParam(
                INFERENCE_ID_FIELD,
                true,
                mapper -> ((SemanticFieldType) mapper.fieldType()).inferenceId,
                getDefaultInferenceId()
            ).addValidator(v -> {
                if (Strings.isEmpty(v)) {
                    throw new IllegalArgumentException(
                        "[" + INFERENCE_ID_FIELD + "] on mapper [" + leafName() + "] of type [" + contentType() + "] must not be empty"
                    );
                }
            }).alwaysSerialize();
        }

        protected String getDefaultInferenceId() {
            return null;
        }

        protected Parameter<String> configureSearchInferenceIdParam() {
            return Parameter.stringParam(
                SEARCH_INFERENCE_ID_FIELD,
                true,
                mapper -> ((SemanticFieldType) mapper.fieldType()).searchInferenceId,
                null
            ).acceptsNull().addValidator(v -> {
                if (v != null && Strings.isEmpty(v)) {
                    throw new IllegalArgumentException(
                        "["
                            + SEARCH_INFERENCE_ID_FIELD
                            + "] on mapper ["
                            + leafName()
                            + "] of type ["
                            + contentType()
                            + "] must not be empty"
                    );
                }
            });
        }

        protected Parameter<MinimalServiceSettings> configureModelSettingsParam() {
            return new Parameter<>(
                MODEL_SETTINGS_FIELD,
                true,
                () -> null,
                (n, c, o) -> SemanticTextField.parseModelSettingsFromMap(o),
                mapper -> ((SemanticFieldType) mapper.fieldType()).modelSettings,
                (b, n, v) -> {
                    if (v != null) {
                        b.field(MODEL_SETTINGS_FIELD, v.getFilteredXContentObject());
                    }
                },
                Objects::toString
            ).acceptsNull().setMergeValidator(SemanticFieldMapper::canMergeModelSettings);
        }

        protected Parameter<SemanticTextIndexOptions> configureIndexOptionsParam() {
            return new Parameter<>(
                INDEX_OPTIONS_FIELD,
                true,
                () -> null,
                (n, c, o) -> parseIndexOptionsFromMap(n, o, c.indexVersionCreated(), experimentalFeaturesEnabled),
                mapper -> ((SemanticFieldType) mapper.fieldType()).indexOptions,
                (b, n, v) -> {
                    // TODO: Fix
                    if (v != null) {
                        b.field(INDEX_OPTIONS_FIELD, v);
                    }
                },
                Objects::toString
            ).acceptsNull();
        }

        protected Parameter<ChunkingSettings> configureChunkingSettingsParam() {
            return new Parameter<>(
                CHUNKING_SETTINGS_FIELD,
                true,
                () -> null,
                (n, c, o) -> SemanticTextField.parseChunkingSettingsFromMap(o),
                mapper -> ((SemanticFieldType) mapper.fieldType()).chunkingSettings,
                XContentBuilder::field,
                Objects::toString
            ).acceptsNull();
        }

        protected Parameter<Map<String, String>> configureMetaParam() {
            return Parameter.metaParam();
        }

        @Override
        protected Parameter<?>[] getParameters() {
            return new Parameter<?>[] { inferenceId, searchInferenceId, modelSettings, chunkingSettings, indexOptions, meta };
        }

        @Override
        public String contentType() {
            return CONTENT_TYPE;
        }

        @Override
        public FieldMapper build(MapperBuilderContext context) {
            return null;
        }
    }

    protected final ModelRegistry modelRegistry;
    protected final List<VectorsFormatProvider> vectorsFormatProviders;

    SemanticFieldMapper(
        String simpleName,
        MappedFieldType mappedFieldType,
        BuilderParams builderParams,
        ModelRegistry modelRegistry,
        List<VectorsFormatProvider> vectorsFormatProviders
    ) {
        super(simpleName, mappedFieldType, builderParams);
        ensureMultiFields(builderParams.multiFields().iterator());
        this.modelRegistry = modelRegistry;
        this.vectorsFormatProviders = vectorsFormatProviders;
    }

    private void ensureMultiFields(Iterator<FieldMapper> mappers) {
        while (mappers.hasNext()) {
            var mapper = mappers.next();
            if (mapper.leafName().equals(INFERENCE_FIELD)) {
                throw new IllegalArgumentException(
                    "Field ["
                        + mapper.fullPath()
                        + "] is already used by another field ["
                        + fullPath()
                        + "] internally. Please choose a different name."
                );
            }
        }
    }

    @Override
    protected void parseCreateField(DocumentParserContext context) throws IOException {
        // TODO: Implement
    }

    @Override
    public FieldMapper.Builder getMergeBuilder() {
        // TODO: Implement
        return null;
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public InferenceFieldMetadata getMetadata(Set<String> sourcePaths) {
        // TODO: Implement
        return null;
    }

    public static class SemanticFieldType extends SimpleMappedFieldType {
        protected final String inferenceId;
        protected final String searchInferenceId;
        protected final MinimalServiceSettings modelSettings;
        protected final ChunkingSettings chunkingSettings;
        protected final SemanticTextIndexOptions indexOptions;
        protected final ObjectMapper inferenceField;

        public SemanticFieldType(
            String name,
            String inferenceId,
            String searchInferenceId,
            MinimalServiceSettings modelSettings,
            ChunkingSettings chunkingSettings,
            SemanticTextIndexOptions indexOptions,
            ObjectMapper inferenceField,
            Map<String, String> meta
        ) {
            super(name, IndexType.terms(true, false), false, meta);
            this.inferenceId = inferenceId;
            this.searchInferenceId = searchInferenceId;
            this.modelSettings = modelSettings;
            this.chunkingSettings = chunkingSettings;
            this.indexOptions = indexOptions;
            this.inferenceField = inferenceField;
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        public String getInferenceId() {
            return inferenceId;
        }

        public String getSearchInferenceId() {
            return searchInferenceId == null ? inferenceId : searchInferenceId;
        }

        public MinimalServiceSettings getModelSettings() {
            return modelSettings;
        }

        public ChunkingSettings getChunkingSettings() {
            return chunkingSettings;
        }

        public SemanticTextIndexOptions getIndexOptions() {
            return indexOptions;
        }

        public ObjectMapper getInferenceField() {
            return inferenceField;
        }

        public NestedObjectMapper getChunksField() {
            return (NestedObjectMapper) inferenceField.getMapper(CHUNKS_FIELD);
        }

        public FieldMapper getEmbeddingsField() {
            return (FieldMapper) getChunksField().getMapper(CHUNKED_EMBEDDINGS_FIELD);
        }

        public FieldMapper getOffsetsField() {
            return (FieldMapper) getChunksField().getMapper(CHUNKED_OFFSET_FIELD);
        }

        @Override
        public Query termQuery(Object value, SearchExecutionContext context) {
            throw new IllegalArgumentException(typeName() + " fields do not support term query");
        }

        @Override
        public Query existsQuery(SearchExecutionContext context) {
            // If this field has never seen inference results (no model settings), there are no values yet
            if (modelSettings == null) {
                return Queries.NO_DOCS_INSTANCE;
            }

            return NestedQueryBuilder.toQuery(
                (c -> getEmbeddingsField().fieldType().existsQuery(c)),
                getChunksFieldName(name()),
                ScoreMode.None,
                false,
                context
            );
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder(FieldDataContext fieldDataContext) {
            throw new IllegalArgumentException("[" + typeName() + "] fields do not support sorting, scripting or aggregating");
        }

        @Override
        public boolean fieldHasValue(FieldInfos fieldInfos) {
            return fieldInfos.fieldInfo(getEmbeddingsFieldName(name())) != null;
        }

        @Override
        public ValueFetcher valueFetcher(SearchExecutionContext context, String format) {
            // TODO: Implement
            return null;
        }

        @Override
        public BlockLoader blockLoader(BlockLoaderContext blContext) {
            // TODO: Implement
            return null;
        }
    }

    public static boolean canMergeModelSettings(MinimalServiceSettings previous, MinimalServiceSettings current, Conflicts conflicts) {
        if (previous != null && current != null && previous.canMergeWith(current)) {
            return true;
        }
        if (previous == null || current == null) {
            return true;
        }
        conflicts.addConflict("model_settings", "");
        return false;
    }

    protected static SemanticTextIndexOptions parseIndexOptionsFromMap(
        String fieldName,
        Object node,
        IndexVersion indexVersion,
        boolean experimentalFeaturesEnabled
    ) {
        if (node == null) {
            return null;
        }

        Map<String, Object> map = XContentMapValues.nodeMapValue(node, INDEX_OPTIONS_FIELD);
        if (map.size() != 1) {
            throw new IllegalArgumentException("Too many index options provided, found [" + map.keySet() + "]");
        }
        Map.Entry<String, Object> entry = map.entrySet().iterator().next();
        SemanticTextIndexOptions.SupportedIndexOptions indexOptions = SemanticTextIndexOptions.SupportedIndexOptions.fromValue(
            entry.getKey()
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> indexOptionsMap = (Map<String, Object>) entry.getValue();
        return new SemanticTextIndexOptions(
            indexOptions,
            indexOptions.parseIndexOptions(fieldName, indexOptionsMap, indexVersion, experimentalFeaturesEnabled)
        );
    }
}
