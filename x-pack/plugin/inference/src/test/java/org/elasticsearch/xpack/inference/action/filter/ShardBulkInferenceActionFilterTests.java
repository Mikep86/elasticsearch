/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.action.filter;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkItemRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkShardRequest;
import org.elasticsearch.action.bulk.BulkShardResponse;
import org.elasticsearch.action.bulk.TransportShardBulkAction;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.InferenceFieldMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.IndexingPressure;
import org.elasticsearch.index.mapper.InferenceMetadataFieldsMapper;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.inference.ChunkedInference;
import org.elasticsearch.inference.InferenceService;
import org.elasticsearch.inference.InferenceServiceRegistry;
import org.elasticsearch.inference.MinimalServiceSettings;
import org.elasticsearch.inference.Model;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.inference.UnparsedModel;
import org.elasticsearch.license.MockLicenseState;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.xpack.core.XPackField;
import org.elasticsearch.xpack.core.inference.results.ChunkedInferenceEmbedding;
import org.elasticsearch.xpack.core.inference.results.ChunkedInferenceError;
import org.elasticsearch.xpack.inference.InferenceException;
import org.elasticsearch.xpack.inference.InferencePlugin;
import org.elasticsearch.xpack.inference.mapper.SemanticTextField;
import org.elasticsearch.xpack.inference.model.TestModel;
import org.elasticsearch.xpack.inference.registry.ModelRegistry;
import org.junit.After;
import org.junit.Before;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.elasticsearch.index.IndexingPressure.MAX_COORDINATING_BYTES;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertToXContentEquivalent;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.awaitLatch;
import static org.elasticsearch.xpack.inference.action.filter.ShardBulkInferenceActionFilter.INDICES_INFERENCE_BATCH_SIZE;
import static org.elasticsearch.xpack.inference.action.filter.ShardBulkInferenceActionFilter.getIndexRequestOrNull;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.getChunksFieldName;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextField.getOriginalTextFieldName;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldMapperTests.addSemanticTextInferenceResults;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldTests.randomChunkedInferenceEmbedding;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldTests.randomSemanticText;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldTests.randomSemanticTextInput;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldTests.semanticTextFieldFromChunkedInferenceResults;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldTests.toChunkedResult;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ShardBulkInferenceActionFilterTests extends ESTestCase {
    private static final Object EXPLICIT_NULL = new Object();

    private final boolean useLegacyFormat;
    private ThreadPool threadPool;

    public ShardBulkInferenceActionFilterTests(boolean useLegacyFormat) {
        this.useLegacyFormat = useLegacyFormat;
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() throws Exception {
        return List.of(new Object[] { true }, new Object[] { false });
    }

    @Before
    public void setupThreadPool() {
        threadPool = new TestThreadPool(getTestName());
    }

    @After
    public void tearDownThreadPool() throws Exception {
        terminate(threadPool);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testFilterNoop() throws Exception {
        ShardBulkInferenceActionFilter filter = createFilter(threadPool, Map.of(), useLegacyFormat, true);
        CountDownLatch chainExecuted = new CountDownLatch(1);
        ActionFilterChain actionFilterChain = (task, action, request, listener) -> {
            try {
                assertNull(((BulkShardRequest) request).getInferenceFieldMap());
            } finally {
                chainExecuted.countDown();
            }
        };
        ActionListener actionListener = mock(ActionListener.class);
        Task task = mock(Task.class);
        BulkShardRequest request = new BulkShardRequest(
            new ShardId("test", "test", 0),
            WriteRequest.RefreshPolicy.NONE,
            new BulkItemRequest[0]
        );
        request.setInferenceFieldMap(
            Map.of("foo", new InferenceFieldMetadata("foo", "bar", "baz", generateRandomStringArray(5, 10, false, false)))
        );
        filter.apply(task, TransportShardBulkAction.ACTION_NAME, request, actionListener, actionFilterChain);
        awaitLatch(chainExecuted, 10, TimeUnit.SECONDS);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testLicenseInvalidForInference() throws InterruptedException {
        StaticModel model = StaticModel.createRandomInstance();
        ShardBulkInferenceActionFilter filter = createFilter(threadPool, Map.of(), useLegacyFormat, false);
        CountDownLatch chainExecuted = new CountDownLatch(1);
        ActionFilterChain actionFilterChain = (task, action, request, listener) -> {
            try {
                BulkShardRequest bulkShardRequest = (BulkShardRequest) request;
                assertThat(bulkShardRequest.items().length, equalTo(1));

                BulkItemResponse.Failure failure = bulkShardRequest.items()[0].getPrimaryResponse().getFailure();
                assertNotNull(failure);
                assertThat(failure.getCause(), instanceOf(ElasticsearchSecurityException.class));
                assertThat(
                    failure.getMessage(),
                    containsString(org.elasticsearch.core.Strings.format("current license is non-compliant for [%s]", XPackField.INFERENCE))
                );
            } finally {
                chainExecuted.countDown();
            }

        };
        ActionListener actionListener = mock(ActionListener.class);
        Task task = mock(Task.class);

        Map<String, InferenceFieldMetadata> inferenceFieldMap = Map.of(
            "obj.field1",
            new InferenceFieldMetadata("obj.field1", model.getInferenceEntityId(), new String[] { "obj.field1" })
        );
        BulkItemRequest[] items = new BulkItemRequest[1];
        items[0] = new BulkItemRequest(0, new IndexRequest("test").source("obj.field1", "Test"));
        BulkShardRequest request = new BulkShardRequest(new ShardId("test", "test", 0), WriteRequest.RefreshPolicy.NONE, items);
        request.setInferenceFieldMap(inferenceFieldMap);

        filter.apply(task, TransportShardBulkAction.ACTION_NAME, request, actionListener, actionFilterChain);
        awaitLatch(chainExecuted, 10, TimeUnit.SECONDS);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testInferenceNotFound() throws Exception {
        StaticModel model = StaticModel.createRandomInstance();
        ShardBulkInferenceActionFilter filter = createFilter(
            threadPool,
            Map.of(model.getInferenceEntityId(), model),
            useLegacyFormat,
            true
        );
        CountDownLatch chainExecuted = new CountDownLatch(1);
        ActionFilterChain actionFilterChain = (task, action, request, listener) -> {
            try {
                BulkShardRequest bulkShardRequest = (BulkShardRequest) request;
                assertNull(bulkShardRequest.getInferenceFieldMap());
                for (BulkItemRequest item : bulkShardRequest.items()) {
                    assertNotNull(item.getPrimaryResponse());
                    assertTrue(item.getPrimaryResponse().isFailed());
                    BulkItemResponse.Failure failure = item.getPrimaryResponse().getFailure();
                    assertThat(failure.getStatus(), equalTo(RestStatus.NOT_FOUND));
                }
            } finally {
                chainExecuted.countDown();
            }
        };
        ActionListener actionListener = mock(ActionListener.class);
        Task task = mock(Task.class);

        Map<String, InferenceFieldMetadata> inferenceFieldMap = Map.of(
            "field1",
            new InferenceFieldMetadata("field1", model.getInferenceEntityId(), new String[] { "field1" }),
            "field2",
            new InferenceFieldMetadata("field2", "inference_0", new String[] { "field2" }),
            "field3",
            new InferenceFieldMetadata("field3", "inference_0", new String[] { "field3" })
        );
        BulkItemRequest[] items = new BulkItemRequest[10];
        for (int i = 0; i < items.length; i++) {
            items[i] = randomBulkItemRequest(useLegacyFormat, Map.of(), inferenceFieldMap)[0];
        }
        BulkShardRequest request = new BulkShardRequest(new ShardId("test", "test", 0), WriteRequest.RefreshPolicy.NONE, items);
        request.setInferenceFieldMap(inferenceFieldMap);
        filter.apply(task, TransportShardBulkAction.ACTION_NAME, request, actionListener, actionFilterChain);
        awaitLatch(chainExecuted, 10, TimeUnit.SECONDS);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testItemFailures() throws Exception {
        StaticModel model = StaticModel.createRandomInstance(TaskType.SPARSE_EMBEDDING);

        ShardBulkInferenceActionFilter filter = createFilter(
            threadPool,
            Map.of(model.getInferenceEntityId(), model),
            useLegacyFormat,
            true
        );
        model.putResult("I am a failure", new ChunkedInferenceError(new IllegalArgumentException("boom")));
        model.putResult("I am a success", randomChunkedInferenceEmbedding(model, List.of("I am a success")));
        CountDownLatch chainExecuted = new CountDownLatch(1);
        ActionFilterChain actionFilterChain = (task, action, request, listener) -> {
            try {
                BulkShardRequest bulkShardRequest = (BulkShardRequest) request;
                assertNull(bulkShardRequest.getInferenceFieldMap());
                assertThat(bulkShardRequest.items().length, equalTo(3));

                // item 0 is a failure
                assertNotNull(bulkShardRequest.items()[0].getPrimaryResponse());
                assertTrue(bulkShardRequest.items()[0].getPrimaryResponse().isFailed());
                BulkItemResponse.Failure failure = bulkShardRequest.items()[0].getPrimaryResponse().getFailure();
                assertThat(failure.getCause().getMessage(), containsString("Exception when running inference"));
                assertThat(failure.getCause().getCause().getMessage(), containsString("boom"));
                assertThat(failure.getStatus(), is(RestStatus.BAD_REQUEST));

                // item 1 is a success
                assertNull(bulkShardRequest.items()[1].getPrimaryResponse());
                IndexRequest actualRequest = getIndexRequestOrNull(bulkShardRequest.items()[1].request());
                assertThat(
                    XContentMapValues.extractValue(useLegacyFormat ? "field1.text" : "field1", actualRequest.sourceAsMap()),
                    equalTo("I am a success")
                );
                if (useLegacyFormat == false) {
                    assertNotNull(
                        XContentMapValues.extractValue(InferenceMetadataFieldsMapper.NAME + ".field1", actualRequest.sourceAsMap())
                    );
                }

                // item 2 is a failure
                assertNotNull(bulkShardRequest.items()[2].getPrimaryResponse());
                assertTrue(bulkShardRequest.items()[2].getPrimaryResponse().isFailed());
                failure = bulkShardRequest.items()[2].getPrimaryResponse().getFailure();
                assertThat(failure.getCause().getMessage(), containsString("Exception when running inference"));
                assertThat(failure.getCause().getCause().getMessage(), containsString("boom"));
                assertThat(failure.getStatus(), is(RestStatus.BAD_REQUEST));
            } finally {
                chainExecuted.countDown();
            }
        };
        ActionListener actionListener = mock(ActionListener.class);
        Task task = mock(Task.class);

        Map<String, InferenceFieldMetadata> inferenceFieldMap = Map.of(
            "field1",
            new InferenceFieldMetadata("field1", model.getInferenceEntityId(), new String[] { "field1" })
        );
        BulkItemRequest[] items = new BulkItemRequest[3];
        items[0] = new BulkItemRequest(0, new IndexRequest("index").source("field1", "I am a failure"));
        items[1] = new BulkItemRequest(1, new IndexRequest("index").source("field1", "I am a success"));
        items[2] = new BulkItemRequest(2, new IndexRequest("index").source("field1", "I am a failure"));
        BulkShardRequest request = new BulkShardRequest(new ShardId("test", "test", 0), WriteRequest.RefreshPolicy.NONE, items);
        request.setInferenceFieldMap(inferenceFieldMap);
        filter.apply(task, TransportShardBulkAction.ACTION_NAME, request, actionListener, actionFilterChain);
        awaitLatch(chainExecuted, 10, TimeUnit.SECONDS);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testExplicitNull() throws Exception {
        StaticModel model = StaticModel.createRandomInstance(TaskType.SPARSE_EMBEDDING);
        model.putResult("I am a failure", new ChunkedInferenceError(new IllegalArgumentException("boom")));
        model.putResult("I am a success", randomChunkedInferenceEmbedding(model, List.of("I am a success")));

        ShardBulkInferenceActionFilter filter = createFilter(
            threadPool,
            Map.of(model.getInferenceEntityId(), model),
            useLegacyFormat,
            true
        );

        CountDownLatch chainExecuted = new CountDownLatch(1);
        ActionFilterChain actionFilterChain = (task, action, request, listener) -> {
            try {
                BulkShardRequest bulkShardRequest = (BulkShardRequest) request;
                assertNull(bulkShardRequest.getInferenceFieldMap());
                assertThat(bulkShardRequest.items().length, equalTo(5));

                // item 0
                assertNull(bulkShardRequest.items()[0].getPrimaryResponse());
                IndexRequest actualRequest = getIndexRequestOrNull(bulkShardRequest.items()[0].request());
                assertThat(XContentMapValues.extractValue("obj.field1", actualRequest.sourceAsMap(), EXPLICIT_NULL), is(EXPLICIT_NULL));
                assertNull(XContentMapValues.extractValue(InferenceMetadataFieldsMapper.NAME, actualRequest.sourceAsMap(), EXPLICIT_NULL));

                // item 1 is a success
                assertNull(bulkShardRequest.items()[1].getPrimaryResponse());
                actualRequest = getIndexRequestOrNull(bulkShardRequest.items()[1].request());
                assertInferenceResults(useLegacyFormat, actualRequest, "obj.field1", "I am a success", 1);

                // item 2 is a failure
                assertNotNull(bulkShardRequest.items()[2].getPrimaryResponse());
                assertTrue(bulkShardRequest.items()[2].getPrimaryResponse().isFailed());
                var failure = bulkShardRequest.items()[2].getPrimaryResponse().getFailure();
                assertThat(failure.getCause().getCause().getMessage(), containsString("boom"));

                // item 3
                assertNull(bulkShardRequest.items()[3].getPrimaryResponse());
                actualRequest = getIndexRequestOrNull(bulkShardRequest.items()[3].request());
                assertInferenceResults(useLegacyFormat, actualRequest, "obj.field1", EXPLICIT_NULL, null);

                // item 4
                assertNull(bulkShardRequest.items()[4].getPrimaryResponse());
                actualRequest = getIndexRequestOrNull(bulkShardRequest.items()[4].request());
                assertNull(XContentMapValues.extractValue("obj.field1", actualRequest.sourceAsMap(), EXPLICIT_NULL));
                assertNull(XContentMapValues.extractValue(InferenceMetadataFieldsMapper.NAME, actualRequest.sourceAsMap(), EXPLICIT_NULL));
            } finally {
                chainExecuted.countDown();
            }
        };
        ActionListener actionListener = mock(ActionListener.class);
        Task task = mock(Task.class);

        Map<String, InferenceFieldMetadata> inferenceFieldMap = Map.of(
            "obj.field1",
            new InferenceFieldMetadata("obj.field1", model.getInferenceEntityId(), new String[] { "obj.field1" })
        );
        Map<String, Object> sourceWithNull = new HashMap<>();
        sourceWithNull.put("field1", null);

        BulkItemRequest[] items = new BulkItemRequest[5];
        items[0] = new BulkItemRequest(0, new IndexRequest("index").source(Map.of("obj", sourceWithNull)));
        items[1] = new BulkItemRequest(1, new IndexRequest("index").source("obj.field1", "I am a success"));
        items[2] = new BulkItemRequest(2, new IndexRequest("index").source("obj.field1", "I am a failure"));
        items[3] = new BulkItemRequest(3, new UpdateRequest().doc(new IndexRequest("index").source(Map.of("obj", sourceWithNull))));
        items[4] = new BulkItemRequest(4, new UpdateRequest().doc(new IndexRequest("index").source(Map.of("field2", "value"))));
        BulkShardRequest request = new BulkShardRequest(new ShardId("test", "test", 0), WriteRequest.RefreshPolicy.NONE, items);
        request.setInferenceFieldMap(inferenceFieldMap);
        filter.apply(task, TransportShardBulkAction.ACTION_NAME, request, actionListener, actionFilterChain);
        awaitLatch(chainExecuted, 10, TimeUnit.SECONDS);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testHandleEmptyInput() throws Exception {
        StaticModel model = StaticModel.createRandomInstance();
        ShardBulkInferenceActionFilter filter = createFilter(
            threadPool,
            Map.of(model.getInferenceEntityId(), model),
            useLegacyFormat,
            true
        );

        CountDownLatch chainExecuted = new CountDownLatch(1);
        ActionFilterChain actionFilterChain = (task, action, request, listener) -> {
            try {
                BulkShardRequest bulkShardRequest = (BulkShardRequest) request;
                assertNull(bulkShardRequest.getInferenceFieldMap());
                assertThat(bulkShardRequest.items().length, equalTo(3));

                // Create with Empty string
                assertNull(bulkShardRequest.items()[0].getPrimaryResponse());
                IndexRequest actualRequest = getIndexRequestOrNull(bulkShardRequest.items()[0].request());
                assertInferenceResults(useLegacyFormat, actualRequest, "semantic_text_field", "", 0);

                // Create with whitespace only
                assertNull(bulkShardRequest.items()[1].getPrimaryResponse());
                actualRequest = getIndexRequestOrNull(bulkShardRequest.items()[1].request());
                assertInferenceResults(useLegacyFormat, actualRequest, "semantic_text_field", " ", 0);

                // Update with multiple Whitespaces
                assertNull(bulkShardRequest.items()[2].getPrimaryResponse());
                actualRequest = getIndexRequestOrNull(bulkShardRequest.items()[2].request());
                assertInferenceResults(useLegacyFormat, actualRequest, "semantic_text_field", "  ", 0);
            } finally {
                chainExecuted.countDown();
            }
        };
        ActionListener actionListener = mock(ActionListener.class);
        Task task = mock(Task.class);
        Map<String, InferenceFieldMetadata> inferenceFieldMap = Map.of(
            "semantic_text_field",
            new InferenceFieldMetadata("semantic_text_field", model.getInferenceEntityId(), new String[] { "semantic_text_field" })
        );

        BulkItemRequest[] items = new BulkItemRequest[3];
        items[0] = new BulkItemRequest(0, new IndexRequest("index").source(Map.of("semantic_text_field", "")));
        items[1] = new BulkItemRequest(1, new IndexRequest("index").source(Map.of("semantic_text_field", " ")));
        items[2] = new BulkItemRequest(2, new UpdateRequest().doc(new IndexRequest("index").source(Map.of("semantic_text_field", "  "))));
        BulkShardRequest request = new BulkShardRequest(new ShardId("test", "test", 0), WriteRequest.RefreshPolicy.NONE, items);
        request.setInferenceFieldMap(inferenceFieldMap);
        filter.apply(task, TransportShardBulkAction.ACTION_NAME, request, actionListener, actionFilterChain);
        awaitLatch(chainExecuted, 10, TimeUnit.SECONDS);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testManyRandomDocs() throws Exception {
        Map<String, StaticModel> inferenceModelMap = new HashMap<>();
        int numModels = randomIntBetween(1, 3);
        for (int i = 0; i < numModels; i++) {
            StaticModel model = StaticModel.createRandomInstance();
            inferenceModelMap.put(model.getInferenceEntityId(), model);
        }

        int numInferenceFields = randomIntBetween(1, 3);
        Map<String, InferenceFieldMetadata> inferenceFieldMap = new HashMap<>();
        for (int i = 0; i < numInferenceFields; i++) {
            String field = randomAlphaOfLengthBetween(5, 10);
            String inferenceId = randomFrom(inferenceModelMap.keySet());
            inferenceFieldMap.put(field, new InferenceFieldMetadata(field, inferenceId, new String[] { field }));
        }

        int numRequests = atLeast(100);
        BulkItemRequest[] originalRequests = new BulkItemRequest[numRequests];
        BulkItemRequest[] modifiedRequests = new BulkItemRequest[numRequests];
        for (int id = 0; id < numRequests; id++) {
            BulkItemRequest[] res = randomBulkItemRequest(useLegacyFormat, inferenceModelMap, inferenceFieldMap);
            originalRequests[id] = res[0];
            modifiedRequests[id] = res[1];
        }

        ShardBulkInferenceActionFilter filter = createFilter(threadPool, inferenceModelMap, useLegacyFormat, true);
        CountDownLatch chainExecuted = new CountDownLatch(1);
        ActionFilterChain actionFilterChain = (task, action, request, listener) -> {
            try {
                assertThat(request, instanceOf(BulkShardRequest.class));
                BulkShardRequest bulkShardRequest = (BulkShardRequest) request;
                assertNull(bulkShardRequest.getInferenceFieldMap());
                BulkItemRequest[] items = bulkShardRequest.items();
                assertThat(items.length, equalTo(originalRequests.length));
                for (int id = 0; id < items.length; id++) {
                    IndexRequest actualRequest = getIndexRequestOrNull(items[id].request());
                    IndexRequest expectedRequest = getIndexRequestOrNull(modifiedRequests[id].request());
                    try {
                        assertToXContentEquivalent(expectedRequest.source(), actualRequest.source(), expectedRequest.getContentType());
                    } catch (Exception exc) {
                        throw new IllegalStateException(exc);
                    }
                }
            } finally {
                chainExecuted.countDown();
            }
        };
        ActionListener actionListener = mock(ActionListener.class);
        Task task = mock(Task.class);
        BulkShardRequest original = new BulkShardRequest(new ShardId("test", "test", 0), WriteRequest.RefreshPolicy.NONE, originalRequests);
        original.setInferenceFieldMap(inferenceFieldMap);
        filter.apply(task, TransportShardBulkAction.ACTION_NAME, original, actionListener, actionFilterChain);
        awaitLatch(chainExecuted, 10, TimeUnit.SECONDS);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testIndexingPressure() throws Exception {
        final InstrumentedIndexingPressure indexingPressure = new InstrumentedIndexingPressure(Settings.EMPTY);
        final StaticModel sparseModel = StaticModel.createRandomInstance(TaskType.SPARSE_EMBEDDING);
        final StaticModel denseModel = StaticModel.createRandomInstance(TaskType.TEXT_EMBEDDING);
        final Function<XContentBuilder, Long> bytesUsed = b -> BytesReference.bytes(b).ramBytesUsed();
        final ShardBulkInferenceActionFilter filter = createFilter(
            threadPool,
            Map.of(sparseModel.getInferenceEntityId(), sparseModel, denseModel.getInferenceEntityId(), denseModel),
            useLegacyFormat,
            true
        );
        filter.setIndexingPressure(indexingPressure);

        XContentBuilder doc0Source = IndexRequest.getXContentBuilder(XContentType.JSON, "sparse_field", "a test value");
        XContentBuilder doc1Source = IndexRequest.getXContentBuilder(XContentType.JSON, "dense_field", "another test value");
        XContentBuilder doc2Source = IndexRequest.getXContentBuilder(
            XContentType.JSON,
            "sparse_field",
            "a test value",
            "dense_field",
            "another test value"
        );
        XContentBuilder doc3Source = IndexRequest.getXContentBuilder(
            XContentType.JSON,
            "dense_field",
            List.of("value one", "  ", "value two")
        );
        XContentBuilder doc4Source = IndexRequest.getXContentBuilder(XContentType.JSON, "sparse_field", "   ");
        XContentBuilder doc5Source = XContentFactory.contentBuilder(XContentType.JSON);
        {
            doc5Source.startObject();
            if (useLegacyFormat == false) {
                doc5Source.field("sparse_field", "a test value");
            }
            addSemanticTextInferenceResults(
                useLegacyFormat,
                doc5Source,
                List.of(randomSemanticText(useLegacyFormat, "sparse_field", sparseModel, List.of("a test value"), XContentType.JSON))
            );
            doc5Source.endObject();
        }
        XContentBuilder doc0UpdateSource = IndexRequest.getXContentBuilder(XContentType.JSON, "sparse_field", "an updated value");
        XContentBuilder doc1UpdateSource = IndexRequest.getXContentBuilder(XContentType.JSON, "dense_field", null);

        CountDownLatch chainExecuted = new CountDownLatch(1);
        ActionFilterChain actionFilterChain = (task, action, request, listener) -> {
            try {
                BulkShardRequest bulkShardRequest = (BulkShardRequest) request;
                assertNull(bulkShardRequest.getInferenceFieldMap());
                assertThat(bulkShardRequest.items().length, equalTo(10));

                for (BulkItemRequest item : bulkShardRequest.items()) {
                    assertNull(item.getPrimaryResponse());
                }

                IndexingPressure.Coordinating coordinatingIndexingPressure = indexingPressure.getCoordinating();
                assertThat(coordinatingIndexingPressure, notNullValue());
                verify(coordinatingIndexingPressure).increment(1, bytesUsed.apply(doc0Source));
                verify(coordinatingIndexingPressure).increment(1, bytesUsed.apply(doc1Source));
                verify(coordinatingIndexingPressure).increment(1, bytesUsed.apply(doc2Source));
                verify(coordinatingIndexingPressure).increment(1, bytesUsed.apply(doc3Source));
                verify(coordinatingIndexingPressure).increment(1, bytesUsed.apply(doc4Source));
                verify(coordinatingIndexingPressure).increment(1, bytesUsed.apply(doc0UpdateSource));
                if (useLegacyFormat == false) {
                    verify(coordinatingIndexingPressure).increment(1, bytesUsed.apply(doc1UpdateSource));
                }

                verify(coordinatingIndexingPressure, times(useLegacyFormat ? 6 : 7)).increment(eq(0), longThat(l -> l > 0));

                // Verify that the only times that increment is called are the times verified above
                verify(coordinatingIndexingPressure, times(useLegacyFormat ? 12 : 14)).increment(anyInt(), anyLong());

                // Verify that the coordinating indexing pressure is maintained through downstream action filters
                verify(coordinatingIndexingPressure, never()).close();

                // Call the listener once the request is successfully processed, like is done in the production code path
                listener.onResponse(null);
            } finally {
                chainExecuted.countDown();
            }
        };
        ActionListener actionListener = mock(ActionListener.class);
        Task task = mock(Task.class);

        Map<String, InferenceFieldMetadata> inferenceFieldMap = Map.of(
            "sparse_field",
            new InferenceFieldMetadata("sparse_field", sparseModel.getInferenceEntityId(), new String[] { "sparse_field" }),
            "dense_field",
            new InferenceFieldMetadata("dense_field", denseModel.getInferenceEntityId(), new String[] { "dense_field" })
        );

        BulkItemRequest[] items = new BulkItemRequest[10];
        items[0] = new BulkItemRequest(0, new IndexRequest("index").id("doc_0").source(doc0Source));
        items[1] = new BulkItemRequest(1, new IndexRequest("index").id("doc_1").source(doc1Source));
        items[2] = new BulkItemRequest(2, new IndexRequest("index").id("doc_2").source(doc2Source));
        items[3] = new BulkItemRequest(3, new IndexRequest("index").id("doc_3").source(doc3Source));
        items[4] = new BulkItemRequest(4, new IndexRequest("index").id("doc_4").source(doc4Source));
        items[5] = new BulkItemRequest(5, new IndexRequest("index").id("doc_5").source(doc5Source));
        items[6] = new BulkItemRequest(6, new IndexRequest("index").id("doc_6").source("non_inference_field", "yet another test value"));
        items[7] = new BulkItemRequest(7, new UpdateRequest().doc(new IndexRequest("index").id("doc_0").source(doc0UpdateSource)));
        items[8] = new BulkItemRequest(8, new UpdateRequest().doc(new IndexRequest("index").id("doc_1").source(doc1UpdateSource)));
        items[9] = new BulkItemRequest(
            9,
            new UpdateRequest().doc(new IndexRequest("index").id("doc_3").source("non_inference_field", "yet another updated value"))
        );

        BulkShardRequest request = new BulkShardRequest(new ShardId("test", "test", 0), WriteRequest.RefreshPolicy.NONE, items);
        request.setInferenceFieldMap(inferenceFieldMap);
        filter.apply(task, TransportShardBulkAction.ACTION_NAME, request, actionListener, actionFilterChain);
        awaitLatch(chainExecuted, 10, TimeUnit.SECONDS);

        IndexingPressure.Coordinating coordinatingIndexingPressure = indexingPressure.getCoordinating();
        assertThat(coordinatingIndexingPressure, notNullValue());
        verify(coordinatingIndexingPressure).close();
    }

    @SuppressWarnings("unchecked")
    public void testIndexingPressureTripsOnEstimatedInferenceBytes() {
        final InstrumentedIndexingPressure indexingPressure = new InstrumentedIndexingPressure(
            Settings.builder().put(MAX_COORDINATING_BYTES.getKey(), "1b").build()
        );
        final StaticModel sparseModel = StaticModel.createRandomInstance(TaskType.SPARSE_EMBEDDING);
        final ShardBulkInferenceActionFilter filter = createFilter(
            threadPool,
            Map.of(sparseModel.getInferenceEntityId(), sparseModel),
            useLegacyFormat,
            true
        );
        filter.setIndexingPressure(indexingPressure);

        ActionFilterChain<BulkShardRequest, BulkShardResponse> actionFilterChain = (task, action, request, listener) -> {
            fail("Downstream elements of the action filter chain should not execute");
        };
        ActionListener<BulkShardResponse> actionListener = (ActionListener<BulkShardResponse>) mock(ActionListener.class);
        Task task = mock(Task.class);

        Map<String, InferenceFieldMetadata> inferenceFieldMap = Map.of(
            "sparse_field",
            new InferenceFieldMetadata("sparse_field", sparseModel.getInferenceEntityId(), new String[] { "sparse_field" })
        );

        BulkItemRequest[] items = new BulkItemRequest[3];
        items[0] = new BulkItemRequest(0, new IndexRequest("index").id("doc_0").source("non_inference_field", "foo"));
        items[1] = new BulkItemRequest(1, new IndexRequest("index").id("doc_1").source("sparse_field", "bar"));
        items[2] = new BulkItemRequest(2, new IndexRequest("index").id("doc_2").source("non_inference_field", "baz"));

        BulkShardRequest request = new BulkShardRequest(new ShardId("test", "test", 0), WriteRequest.RefreshPolicy.NONE, items);
        request.setInferenceFieldMap(inferenceFieldMap);

        InferenceException exception = assertThrows(
            InferenceException.class,
            () -> filter.apply(task, TransportShardBulkAction.ACTION_NAME, request, actionListener, actionFilterChain)
        );
        assertThat(exception.getMessage(), containsString("Insufficient memory available to perform inference on bulk request"));
        assertThat(exception.status(), equalTo(RestStatus.TOO_MANY_REQUESTS));
        assertThat(exception.getCause(), instanceOf(EsRejectedExecutionException.class));

        IndexingPressure.Coordinating coordinatingIndexingPressure = indexingPressure.getCoordinating();
        assertThat(coordinatingIndexingPressure, notNullValue());
        verify(coordinatingIndexingPressure).close();
    }

    @SuppressWarnings("unchecked")
    private static ShardBulkInferenceActionFilter createFilter(
        ThreadPool threadPool,
        Map<String, StaticModel> modelMap,
        boolean useLegacyFormat,
        boolean isLicenseValidForInference
    ) {
        ModelRegistry modelRegistry = mock(ModelRegistry.class);
        Answer<?> unparsedModelAnswer = invocationOnMock -> {
            String id = (String) invocationOnMock.getArguments()[0];
            ActionListener<UnparsedModel> listener = (ActionListener<UnparsedModel>) invocationOnMock.getArguments()[1];
            var model = modelMap.get(id);
            if (model != null) {
                listener.onResponse(
                    new UnparsedModel(
                        model.getInferenceEntityId(),
                        model.getTaskType(),
                        model.getServiceSettings().model(),
                        XContentHelper.convertToMap(JsonXContent.jsonXContent, Strings.toString(model.getTaskSettings()), false),
                        XContentHelper.convertToMap(JsonXContent.jsonXContent, Strings.toString(model.getSecretSettings()), false)
                    )
                );
            } else {
                listener.onFailure(new ResourceNotFoundException("model id [{}] not found", id));
            }
            return null;
        };
        doAnswer(unparsedModelAnswer).when(modelRegistry).getModelWithSecrets(any(), any());

        Answer<MinimalServiceSettings> minimalServiceSettingsAnswer = invocationOnMock -> {
            String inferenceId = (String) invocationOnMock.getArguments()[0];
            var model = modelMap.get(inferenceId);
            if (model == null) {
                throw new ResourceNotFoundException("model id [{}] not found", inferenceId);
            }

            return new MinimalServiceSettings(model);
        };
        doAnswer(minimalServiceSettingsAnswer).when(modelRegistry).getMinimalServiceSettings(any());

        InferenceService inferenceService = mock(InferenceService.class);
        Answer<?> chunkedInferAnswer = invocationOnMock -> {
            StaticModel model = (StaticModel) invocationOnMock.getArguments()[0];
            List<String> inputs = (List<String>) invocationOnMock.getArguments()[2];
            ActionListener<List<ChunkedInference>> listener = (ActionListener<List<ChunkedInference>>) invocationOnMock.getArguments()[6];
            Runnable runnable = () -> {
                List<ChunkedInference> results = new ArrayList<>();
                for (String input : inputs) {
                    results.add(model.getResults(input));
                }
                listener.onResponse(results);
            };
            if (randomBoolean()) {
                try {
                    threadPool.generic().execute(runnable);
                } catch (Exception exc) {
                    listener.onFailure(exc);
                }
            } else {
                runnable.run();
            }
            return null;
        };
        doAnswer(chunkedInferAnswer).when(inferenceService).chunkedInfer(any(), any(), any(), any(), any(), any(), any());

        Answer<Model> modelAnswer = invocationOnMock -> {
            String inferenceId = (String) invocationOnMock.getArguments()[0];
            return modelMap.get(inferenceId);
        };
        doAnswer(modelAnswer).when(inferenceService).parsePersistedConfigWithSecrets(any(), any(), any(), any());

        InferenceServiceRegistry inferenceServiceRegistry = mock(InferenceServiceRegistry.class);
        when(inferenceServiceRegistry.getService(any())).thenReturn(Optional.of(inferenceService));

        MockLicenseState licenseState = MockLicenseState.createMock();
        when(licenseState.isAllowed(InferencePlugin.INFERENCE_API_FEATURE)).thenReturn(isLicenseValidForInference);

        return new ShardBulkInferenceActionFilter(
            createClusterService(useLegacyFormat),
            inferenceServiceRegistry,
            modelRegistry,
            licenseState
        );
    }

    private static ClusterService createClusterService(boolean useLegacyFormat) {
        IndexMetadata indexMetadata = mock(IndexMetadata.class);
        var indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_VERSION_CREATED.getKey(), IndexVersion.current())
            .put(InferenceMetadataFieldsMapper.USE_LEGACY_SEMANTIC_TEXT_FORMAT.getKey(), useLegacyFormat)
            .build();
        when(indexMetadata.getSettings()).thenReturn(indexSettings);

        ProjectMetadata project = spy(ProjectMetadata.builder(Metadata.DEFAULT_PROJECT_ID).build());
        when(project.index(anyString())).thenReturn(indexMetadata);

        Metadata metadata = mock(Metadata.class);
        when(metadata.getProject()).thenReturn(project);

        ClusterState clusterState = ClusterState.builder(new ClusterName("test")).metadata(metadata).build();
        ClusterService clusterService = mock(ClusterService.class);
        when(clusterService.state()).thenReturn(clusterState);
        long batchSizeInBytes = randomLongBetween(1, ByteSizeValue.ofKb(1).getBytes());
        Settings settings = Settings.builder().put(INDICES_INFERENCE_BATCH_SIZE.getKey(), ByteSizeValue.ofBytes(batchSizeInBytes)).build();
        when(clusterService.getSettings()).thenReturn(settings);
        when(clusterService.getClusterSettings()).thenReturn(new ClusterSettings(settings, Set.of(INDICES_INFERENCE_BATCH_SIZE)));
        return clusterService;
    }

    private static BulkItemRequest[] randomBulkItemRequest(
        boolean useLegacyFormat,
        Map<String, StaticModel> modelMap,
        Map<String, InferenceFieldMetadata> fieldInferenceMap
    ) throws IOException {
        Map<String, Object> docMap = new LinkedHashMap<>();
        Map<String, Object> expectedDocMap = new LinkedHashMap<>();
        // force JSON to avoid double/float conversions
        XContentType requestContentType = XContentType.JSON;

        Map<String, Object> inferenceMetadataFields = new HashMap<>();
        for (var entry : fieldInferenceMap.values()) {
            String field = entry.getName();
            var model = modelMap.get(entry.getInferenceId());
            Object inputObject = randomSemanticTextInput();
            String inputText = inputObject.toString();
            docMap.put(field, inputObject);
            expectedDocMap.put(field, useLegacyFormat ? inputText : inputObject);
            if (model == null) {
                // ignore results, the doc should fail with a resource not found exception
                continue;
            }

            SemanticTextField semanticTextField;
            // The model is not field aware and that is why we are skipping the embedding generation process for existing values.
            // This prevents a situation where embeddings in the expected docMap do not match those in the model, which could happen if
            // embeddings were overwritten.
            if (model.hasResult(inputText)) {
                var results = model.getResults(inputText);
                semanticTextField = semanticTextFieldFromChunkedInferenceResults(
                    useLegacyFormat,
                    field,
                    model,
                    List.of(inputText),
                    results,
                    requestContentType
                );
            } else {
                Map<String, List<String>> inputTextMap = Map.of(field, List.of(inputText));
                semanticTextField = randomSemanticText(useLegacyFormat, field, model, List.of(inputText), requestContentType);
                model.putResult(inputText, toChunkedResult(useLegacyFormat, inputTextMap, semanticTextField));
            }

            if (useLegacyFormat) {
                expectedDocMap.put(field, semanticTextField);
            } else {
                inferenceMetadataFields.put(field, semanticTextField);
            }
        }
        if (useLegacyFormat == false) {
            expectedDocMap.put(InferenceMetadataFieldsMapper.NAME, inferenceMetadataFields);
        }

        int requestId = randomIntBetween(0, Integer.MAX_VALUE);
        return new BulkItemRequest[] {
            new BulkItemRequest(requestId, new IndexRequest("index").source(docMap, requestContentType)),
            new BulkItemRequest(requestId, new IndexRequest("index").source(expectedDocMap, requestContentType)) };
    }

    @SuppressWarnings({ "unchecked" })
    private static void assertInferenceResults(
        boolean useLegacyFormat,
        IndexRequest request,
        String fieldName,
        Object expectedOriginalValue,
        Integer expectedChunkCount
    ) {
        final Map<String, Object> requestMap = request.sourceAsMap();
        if (useLegacyFormat) {
            assertThat(
                XContentMapValues.extractValue(getOriginalTextFieldName(fieldName), requestMap, EXPLICIT_NULL),
                equalTo(expectedOriginalValue)
            );

            List<Object> chunks = (List<Object>) XContentMapValues.extractValue(getChunksFieldName(fieldName), requestMap);
            if (expectedChunkCount == null) {
                assertNull(chunks);
            } else {
                assertNotNull(chunks);
                assertThat(chunks.size(), equalTo(expectedChunkCount));
            }
        } else {
            assertThat(XContentMapValues.extractValue(fieldName, requestMap, EXPLICIT_NULL), equalTo(expectedOriginalValue));

            Map<String, Object> inferenceMetadataFields = (Map<String, Object>) XContentMapValues.extractValue(
                InferenceMetadataFieldsMapper.NAME,
                requestMap,
                EXPLICIT_NULL
            );
            assertNotNull(inferenceMetadataFields);

            // When using the inference metadata fields format, chunks are mapped by source field. We handle clearing inference results for
            // a field by emitting an empty chunk list for it. This is done to prevent the clear operation from clearing inference results
            // for other source fields.
            List<Object> chunks = (List<Object>) XContentMapValues.extractValue(
                getChunksFieldName(fieldName) + "." + fieldName,
                inferenceMetadataFields,
                EXPLICIT_NULL
            );

            // When using the new format, the chunks field should always exist
            int expectedSize = expectedChunkCount == null ? 0 : expectedChunkCount;
            assertNotNull(chunks);
            assertThat(chunks.size(), equalTo(expectedSize));
        }
    }

    private static class StaticModel extends TestModel {
        private final Map<String, ChunkedInference> resultMap;

        StaticModel(
            String inferenceEntityId,
            TaskType taskType,
            String service,
            TestServiceSettings serviceSettings,
            TestTaskSettings taskSettings,
            TestSecretSettings secretSettings
        ) {
            super(inferenceEntityId, taskType, service, serviceSettings, taskSettings, secretSettings);
            this.resultMap = new HashMap<>();
        }

        public static StaticModel createRandomInstance() {
            return createRandomInstance(randomFrom(TaskType.TEXT_EMBEDDING, TaskType.SPARSE_EMBEDDING));
        }

        public static StaticModel createRandomInstance(TaskType taskType) {
            TestModel testModel = TestModel.createRandomInstance(taskType);
            return new StaticModel(
                testModel.getInferenceEntityId(),
                testModel.getTaskType(),
                randomAlphaOfLength(10),
                testModel.getServiceSettings(),
                testModel.getTaskSettings(),
                testModel.getSecretSettings()
            );
        }

        ChunkedInference getResults(String text) {
            return resultMap.getOrDefault(text, new ChunkedInferenceEmbedding(List.of()));
        }

        void putResult(String text, ChunkedInference result) {
            resultMap.put(text, result);
        }

        boolean hasResult(String text) {
            return resultMap.containsKey(text);
        }
    }

    private static class InstrumentedIndexingPressure extends IndexingPressure {
        private Coordinating coordinating = null;

        InstrumentedIndexingPressure(Settings settings) {
            super(settings);
        }

        public Coordinating getCoordinating() {
            return coordinating;
        }

        @Override
        public Coordinating createCoordinatingOperation(boolean forceExecution) {
            coordinating = spy(super.createCoordinatingOperation(forceExecution));
            return coordinating;
        }
    }
}
