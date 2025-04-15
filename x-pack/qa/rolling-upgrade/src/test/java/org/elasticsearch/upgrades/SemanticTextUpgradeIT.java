/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.upgrades;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.cache.bitset.BitsetFilterCache;
import org.elasticsearch.index.mapper.InferenceMetadataFieldsMapper;
import org.elasticsearch.index.mapper.MapperMetrics;
import org.elasticsearch.index.mapper.MapperRegistry;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.query.NestedQueryBuilder;
import org.elasticsearch.index.similarity.SimilarityService;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.inference.Model;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.test.IndexSettingsModule;
import org.elasticsearch.test.rest.ObjectPath;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.inference.action.InferenceAction;
import org.elasticsearch.xpack.core.inference.results.SparseEmbeddingResults;
import org.elasticsearch.xpack.core.ml.search.SparseVectorQueryBuilder;
import org.elasticsearch.xpack.core.ml.search.WeightedToken;
import org.elasticsearch.xpack.inference.InferencePlugin;
import org.elasticsearch.xpack.inference.mapper.SemanticTextField;
import org.elasticsearch.xpack.inference.model.TestModel;
import org.junit.BeforeClass;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldMapperTests.addSemanticTextInferenceResults;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldTests.randomSemanticText;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.notNullValue;

public class SemanticTextUpgradeIT extends AbstractUpgradeTestCase {
    private static final String INDEX_BASE_NAME = "semantic_text_test_index";
    private static final String SEMANTIC_TEXT_FIELD = "semantic_field";

    private static Model sparseModel;
    private static MapperService legacyFormatMapperService;
    private static MapperService newFormatMapperService;

    private final boolean useLegacyFormat;

    @BeforeClass
    public static void beforeClass() throws Exception {
        sparseModel = TestModel.createRandomInstance(TaskType.SPARSE_EMBEDDING);
        legacyFormatMapperService = crateMapperService(true);
        newFormatMapperService = crateMapperService(false);
    }

    private static String getIndexName(boolean useLegacyFormat) {
        return INDEX_BASE_NAME + (useLegacyFormat ? "_legacy" : "_new");
    }

    private static String getMapping() {
        return Strings.format("""
            {
              "properties": {
                "%s": {
                  "type": "semantic_text",
                  "inference_id": "%s"
                }
              }
            }
            """, SEMANTIC_TEXT_FIELD, sparseModel.getInferenceEntityId());
    }

    private static MapperService crateMapperService(boolean useLegacyFormat) throws IOException {
        String indexName = getIndexName(useLegacyFormat);
        IndexMetadata indexMetadata = IndexMetadata.builder(indexName)
            .settings(
                Settings.builder()
                    .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
                    .put(IndexMetadata.SETTING_INDEX_UUID, indexName)
                    .put(InferenceMetadataFieldsMapper.USE_LEGACY_SEMANTIC_TEXT_FORMAT.getKey(), useLegacyFormat)
            )
            .numberOfShards(1)
            .numberOfReplicas(0)
            .build();
        IndexSettings indexSettings = IndexSettingsModule.newIndexSettings(indexMetadata);
        SimilarityService similarityService = new SimilarityService(indexSettings, null, Map.of());
        MapperRegistry mapperRegistry = new IndicesModule(List.of(new InferencePlugin(Settings.EMPTY))).getMapperRegistry();
        BitsetFilterCache bitsetFilterCache = new BitsetFilterCache(indexSettings, BitsetFilterCache.Listener.NOOP);

        MapperService mapperService = new MapperService(
            TransportVersion::current,
            indexSettings,
            (type, name) -> Lucene.STANDARD_ANALYZER,
            XContentParserConfiguration.EMPTY,
            similarityService,
            mapperRegistry,
            () -> null,
            indexSettings.getMode().idFieldMapperWithoutFieldData(),
            null,
            bitsetFilterCache::getBitSetProducer,
            MapperMetrics.NOOP
        );
        mapperService.merge("_doc", new CompressedXContent(getMapping()), MapperService.MergeReason.MAPPING_UPDATE);

        return mapperService;
    }

    public SemanticTextUpgradeIT(boolean useLegacyFormat) {
        this.useLegacyFormat = useLegacyFormat;
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() {
        return List.of(new Object[] { true }, new Object[] { false });
    }

    public void testSemanticTextOperations() throws Exception {
        switch (CLUSTER_TYPE) {
            case OLD -> createAndPopulateIndex();
            case MIXED, UPGRADED -> performIndexQueryHighlightOps();
            default -> throw new UnsupportedOperationException("Unknown cluster type [" + CLUSTER_TYPE + "]");
        }
    }

    private void createAndPopulateIndex() throws IOException {
        final String indexName = getIndexName();
        CreateIndexResponse response = createIndex(
            indexName,
            Settings.builder().put(InferenceMetadataFieldsMapper.USE_LEGACY_SEMANTIC_TEXT_FORMAT.getKey(), useLegacyFormat).build(),
            getMapping()
        );
        assertThat(response.isAcknowledged(), equalTo(true));

        indexDoc("doc_1", List.of("a test value", "with multiple test values"));
    }

    private void performIndexQueryHighlightOps() throws IOException {
        indexDoc("doc_2", List.of("another test value"));
        ObjectPath queryObjectPath = semanticQuery("test value", 3);
        assertQueryResponse(queryObjectPath);
    }

    private String getIndexName() {
        return getIndexName(useLegacyFormat);
    }

    private MapperService getMapperService() {
        return useLegacyFormat ? legacyFormatMapperService : newFormatMapperService;
    }

    private void indexDoc(String id, List<String> semanticTextFieldValue) throws IOException {
        final String indexName = getIndexName();
        final SemanticTextField semanticTextField = randomSemanticText(
            useLegacyFormat,
            SEMANTIC_TEXT_FIELD,
            sparseModel,
            null,
            semanticTextFieldValue,
            XContentType.JSON
        );

        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        if (useLegacyFormat == false) {
            builder.field(semanticTextField.fieldName(), semanticTextFieldValue);
        }
        addSemanticTextInferenceResults(useLegacyFormat, builder, List.of(semanticTextField));
        builder.endObject();

        MapperService mapperService = getMapperService();
        SourceToParse sourceToParse = new SourceToParse(id, BytesReference.bytes(builder), XContentType.JSON);
        ParsedDocument parsedDocument = mapperService.documentMapper().parse(sourceToParse);
        mapperService.merge(
            "_doc",
            parsedDocument.dynamicMappingsUpdate().toCompressedXContent(),
            MapperService.MergeReason.MAPPING_UPDATE
        );

        RequestOptions requestOptions = RequestOptions.DEFAULT.toBuilder().addParameter("refresh", "true").build();
        Request request = new Request("POST", indexName + "/_doc/" + id);
        request.setJsonEntity(Strings.toString(builder));
        request.setOptions(requestOptions);

        Response response = client().performRequest(request);
        assertOK(response);
    }

    private ObjectPath semanticQuery(String query, Integer numOfHighlightFragments) throws IOException {
        // We can't perform a real semantic query because that requires performing inference, so instead we perform an equivalent nested
        // query
        List<WeightedToken> weightedTokens = Arrays.stream(query.split("\\s")).map(t -> new WeightedToken(t, 1.0f)).toList();
        SparseVectorQueryBuilder sparseVectorQueryBuilder = new SparseVectorQueryBuilder(
            SemanticTextField.getEmbeddingsFieldName(SEMANTIC_TEXT_FIELD),
            weightedTokens,
            null,
            null,
            null,
            null
        );
        NestedQueryBuilder nestedQueryBuilder = new NestedQueryBuilder(
            SemanticTextField.getChunksFieldName(SEMANTIC_TEXT_FIELD),
            sparseVectorQueryBuilder,
            ScoreMode.Max
        );

        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        builder.field("query", nestedQueryBuilder);
        if (numOfHighlightFragments != null) {
            HighlightBuilder.Field highlightField = new HighlightBuilder.Field(SEMANTIC_TEXT_FIELD);
            highlightField.numOfFragments(numOfHighlightFragments);

            HighlightBuilder highlightBuilder = new HighlightBuilder();
            highlightBuilder.field(highlightField);

            builder.field("highlight", highlightBuilder);
        }
        builder.endObject();

        Request request = new Request("GET", getIndexName() + "/_search");
        request.setJsonEntity(Strings.toString(builder));

        Response response = client().performRequest(request);
        return assertOKAndCreateObjectPath(response);
    }

    @SuppressWarnings("unchecked")
    private static void assertQueryResponse(ObjectPath queryObjectPath) throws IOException {
        final Map<String, List<String>> expectedHighlights = Map.of(
            "doc_1",
            List.of("a test value", "with multiple test values"),
            "doc_2",
            List.of("another test value")
        );

        assertThat(queryObjectPath.evaluate("hits.total.value"), equalTo(2));
        assertThat(queryObjectPath.evaluateArraySize("hits.hits"), equalTo(2));

        Set<String> docIds = new HashSet<>();
        List<Object> hits = queryObjectPath.evaluate("hits.hits");
        for (Object hit : hits) {
            assertThat(hit, instanceOf(Map.class));
            Map<String, Object> hitMap = (Map<String, Object>) hit;

            String id = (String) hitMap.get("_id");
            assertThat(id, notNullValue());
            docIds.add(id);

            List<String> expectedHighlight = expectedHighlights.get(id);
            assertThat(expectedHighlight, notNullValue());
            assertThat(((Map<String, Object>) hitMap.get("highlight")).get(SEMANTIC_TEXT_FIELD), equalTo(expectedHighlight));
        }

        assertThat(docIds, equalTo(Set.of("doc_1", "doc_2")));
    }

    private static class MockInferenceResultQueryRewriter extends AbstractQueryRewriter {
        @Override
        public boolean canSimulateMethod(Method method, Object[] args) throws NoSuchMethodException {
            return method.equals(Client.class.getMethod("execute", ActionType.class, ActionRequest.class, ActionListener.class))
                && (args[0] instanceof InferenceAction);
        }

        @Override
        public Object simulateMethod(Method method, Object[] args) {
            InferenceAction.Request request = (InferenceAction.Request) args[1];
            List<String> input = request.getInput();
            String query = input.get(0);

            InferenceAction.Response response = generateSparseEmbeddingInferenceResponse(query);

            @SuppressWarnings("unchecked")  // We matched the method above.
            ActionListener<InferenceAction.Response> listener = (ActionListener<InferenceAction.Response>) args[2];
            listener.onResponse(response);

            return null;
        }

        private InferenceAction.Response generateSparseEmbeddingInferenceResponse(String query) {
            List<WeightedToken> weightedTokens = Arrays.stream(query.split("\\s+")).map(s -> new WeightedToken(s, 1.0f)).toList();
            return new InferenceAction.Response(
                new SparseEmbeddingResults(List.of(SparseEmbeddingResults.Embedding.create(weightedTokens, false)))
            );
        }
    }
}
