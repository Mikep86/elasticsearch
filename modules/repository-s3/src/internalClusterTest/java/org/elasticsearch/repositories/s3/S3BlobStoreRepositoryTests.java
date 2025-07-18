/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.repositories.s3;

import fixture.s3.S3HttpHandler;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.internal.http.pipeline.stages.ApplyTransactionIdStage;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.MultipartUpload;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse;
import org.elasticsearch.action.support.broadcast.BroadcastResponse;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.metadata.RepositoryMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.BlobStore;
import org.elasticsearch.common.blobstore.BlobStoreActionStats;
import org.elasticsearch.common.blobstore.OperationPurpose;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.Maps;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.indices.recovery.RecoverySettings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.repositories.RepositoriesService;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.repositories.RepositoryData;
import org.elasticsearch.repositories.RepositoryMissingException;
import org.elasticsearch.repositories.RepositoryStats;
import org.elasticsearch.repositories.blobstore.BlobStoreRepository;
import org.elasticsearch.repositories.blobstore.BlobStoreTestUtil;
import org.elasticsearch.repositories.blobstore.ESMockAPIBasedRepositoryIntegTestCase;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.snapshots.SnapshotState;
import org.elasticsearch.snapshots.SnapshotsService;
import org.elasticsearch.snapshots.mockstore.BlobStoreWrapper;
import org.elasticsearch.telemetry.Measurement;
import org.elasticsearch.telemetry.TestTelemetryPlugin;
import org.elasticsearch.test.BackgroundIndexer;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.MockLog;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static fixture.aws.AwsCredentialsUtils.isValidAwsV4SignedAuthorizationHeader;
import static org.elasticsearch.repositories.RepositoriesMetrics.METRIC_REQUESTS_TOTAL;
import static org.elasticsearch.repositories.blobstore.BlobStoreRepository.getRepositoryDataBlobName;
import static org.elasticsearch.repositories.blobstore.BlobStoreTestUtil.randomNonDataPurpose;
import static org.elasticsearch.repositories.blobstore.BlobStoreTestUtil.randomPurpose;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

@SuppressForbidden(reason = "this test uses a HttpServer to emulate an S3 endpoint")
// Need to set up a new cluster for each test because cluster settings use randomized authentication settings
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST)
public class S3BlobStoreRepositoryTests extends ESMockAPIBasedRepositoryIntegTestCase {

    private static final TimeValue TEST_COOLDOWN_PERIOD = TimeValue.timeValueSeconds(10L);

    private String region;
    private final AtomicBoolean shouldFailCompleteMultipartUploadRequest = new AtomicBoolean();

    @Override
    public void setUp() throws Exception {
        if (randomBoolean()) {
            region = "test-region";
        }
        shouldFailCompleteMultipartUploadRequest.set(false);
        super.setUp();
    }

    @Override
    protected String repositoryType() {
        return S3Repository.TYPE;
    }

    @Override
    protected Settings repositorySettings(String repoName) {
        Settings.Builder settingsBuilder = Settings.builder()
            .put(super.repositorySettings(repoName))
            .put(S3Repository.BUCKET_SETTING.getKey(), "bucket")
            .put(S3Repository.CLIENT_NAME.getKey(), "test")
            // Don't cache repository data because some tests manually modify the repository data
            .put(BlobStoreRepository.CACHE_REPOSITORY_DATA.getKey(), false);
        if (randomBoolean()) {
            settingsBuilder.put(S3Repository.BASE_PATH_SETTING.getKey(), randomFrom("test", "test/1"));
        }
        return settingsBuilder.build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(TestS3RepositoryPlugin.class, TestTelemetryPlugin.class);
    }

    @Override
    protected Map<String, HttpHandler> createHttpHandlers() {
        return Collections.singletonMap("/bucket", new S3StatsCollectorHttpHandler(new S3BlobStoreHttpHandler("bucket")));
    }

    @Override
    protected HttpHandler createErroneousHttpHandler(final HttpHandler delegate) {
        return new S3StatsCollectorHttpHandler(new S3ErroneousHttpHandler(delegate, randomIntBetween(2, 3)));
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        final MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString(S3ClientSettings.ACCESS_KEY_SETTING.getConcreteSettingForNamespace("test").getKey(), "test_access_key");
        secureSettings.setString(S3ClientSettings.SECRET_KEY_SETTING.getConcreteSettingForNamespace("test").getKey(), "test_secret_key");

        final Settings.Builder builder = Settings.builder()
            .put(ThreadPool.ESTIMATED_TIME_INTERVAL_SETTING.getKey(), 0) // We have tests that verify an exact wait time
            .put(S3ClientSettings.ENDPOINT_SETTING.getConcreteSettingForNamespace("test").getKey(), httpServerUrl())
            .put(S3ClientSettings.ADD_PURPOSE_CUSTOM_QUERY_PARAMETER.getConcreteSettingForNamespace("test").getKey(), "true")
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .setSecureSettings(secureSettings);

        if (randomBoolean()) {
            builder.put(S3ClientSettings.DISABLE_CHUNKED_ENCODING.getConcreteSettingForNamespace("test").getKey(), randomBoolean());
        }
        if (region != null) {
            builder.put(S3ClientSettings.REGION.getConcreteSettingForNamespace("test").getKey(), region);
        }
        return builder.build();
    }

    public void testAbortRequestStats() throws Exception {
        final String repository = createRepository(randomRepositoryName(), false);

        final String index = "index-no-merges";
        createIndex(index, 1, 0);
        final long nbDocs = randomLongBetween(10_000L, 20_000L);
        try (BackgroundIndexer indexer = new BackgroundIndexer(index, client(), (int) nbDocs)) {
            waitForDocs(nbDocs, indexer);
        }
        flushAndRefresh(index);
        BroadcastResponse forceMerge = client().admin().indices().prepareForceMerge(index).setFlush(true).setMaxNumSegments(1).get();
        assertThat(forceMerge.getSuccessfulShards(), equalTo(1));
        assertHitCount(prepareSearch(index).setSize(0).setTrackTotalHits(true), nbDocs);

        // Intentionally fail snapshot to trigger abortMultipartUpload requests
        shouldFailCompleteMultipartUploadRequest.set(true);
        final String snapshot = "snapshot";
        clusterAdmin().prepareCreateSnapshot(TEST_REQUEST_TIMEOUT, repository, snapshot).setWaitForCompletion(true).setIndices(index).get();
        clusterAdmin().prepareDeleteSnapshot(TEST_REQUEST_TIMEOUT, repository, snapshot).get();

        final RepositoryStats repositoryStats = StreamSupport.stream(
            internalCluster().getInstances(RepositoriesService.class).spliterator(),
            false
        ).map(repositoriesService -> {
            try {
                return repositoriesService.repository(repository);
            } catch (RepositoryMissingException e) {
                return null;
            }
        }).filter(Objects::nonNull).map(Repository::stats).reduce(RepositoryStats::merge).get();

        Map<String, Long> sdkRequestCounts = repositoryStats.actionStats.entrySet()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().requests()));
        assertThat(sdkRequestCounts.get("AbortMultipartObject"), greaterThan(0L));
        assertThat(sdkRequestCounts.get("DeleteObjects"), greaterThan(0L));

        final Map<String, Long> mockCalls = getMockRequestCounts();

        String assertionErrorMsg = String.format("SDK sent [%s] calls and handler measured [%s] calls", sdkRequestCounts, mockCalls);
        assertEquals(assertionErrorMsg, mockCalls, sdkRequestCounts);
    }

    public void testMetrics() throws Exception {
        // Create the repository and perform some activities
        final String repository = createRepository(randomRepositoryName(), false);
        final String index = "index-no-merges";
        createIndex(index, 1, 0);

        final long nbDocs = randomLongBetween(10_000L, 20_000L);
        try (BackgroundIndexer indexer = new BackgroundIndexer(index, client(), (int) nbDocs)) {
            waitForDocs(nbDocs, indexer);
        }
        flushAndRefresh(index);
        BroadcastResponse forceMerge = client().admin().indices().prepareForceMerge(index).setFlush(true).setMaxNumSegments(1).get();
        assertThat(forceMerge.getSuccessfulShards(), equalTo(1));
        assertHitCount(prepareSearch(index).setSize(0).setTrackTotalHits(true), nbDocs);

        final String snapshot = "snapshot";
        assertSuccessfulSnapshot(
            clusterAdmin().prepareCreateSnapshot(TEST_REQUEST_TIMEOUT, repository, snapshot).setWaitForCompletion(true).setIndices(index)
        );
        assertAcked(client().admin().indices().prepareDelete(index));
        assertSuccessfulRestore(
            clusterAdmin().prepareRestoreSnapshot(TEST_REQUEST_TIMEOUT, repository, snapshot).setWaitForCompletion(true)
        );
        ensureGreen(index);
        assertHitCount(prepareSearch(index).setSize(0).setTrackTotalHits(true), nbDocs);
        assertAcked(clusterAdmin().prepareDeleteSnapshot(TEST_REQUEST_TIMEOUT, repository, snapshot).get());

        final Map<S3BlobStore.StatsKey, Long> aggregatedMetrics = new HashMap<>();
        // Compare collected stats and metrics for each node and they should be the same
        for (var nodeName : internalCluster().getNodeNames()) {
            final BlobStoreRepository blobStoreRepository;
            try {
                blobStoreRepository = (BlobStoreRepository) internalCluster().getInstance(RepositoriesService.class, nodeName)
                    .repository(repository);
            } catch (RepositoryMissingException e) {
                continue;
            }

            final BlobStore blobStore = blobStoreRepository.blobStore();
            final BlobStore delegateBlobStore = ((BlobStoreWrapper) blobStore).delegate();
            final S3BlobStore s3BlobStore = (S3BlobStore) delegateBlobStore;
            final Map<S3BlobStore.StatsKey, S3BlobStore.ElasticsearchS3MetricsCollector> statsCollectors = s3BlobStore
                .getStatsCollectors().collectors;

            final var plugins = internalCluster().getInstance(PluginsService.class, nodeName)
                .filterPlugins(TestTelemetryPlugin.class)
                .toList();
            assertThat(plugins, hasSize(1));
            final List<Measurement> metrics = Measurement.combine(plugins.get(0).getLongCounterMeasurement(METRIC_REQUESTS_TOTAL));

            assertThat(
                statsCollectors.keySet().stream().map(S3BlobStore.StatsKey::operation).collect(Collectors.toSet()),
                equalTo(
                    metrics.stream()
                        .map(m -> S3BlobStore.Operation.parse((String) m.attributes().get("operation")))
                        .collect(Collectors.toSet())
                )
            );
            metrics.forEach(metric -> {
                assertThat(
                    metric.attributes(),
                    allOf(hasEntry("repo_type", S3Repository.TYPE), hasKey("repo_name"), hasKey("operation"), hasKey("purpose"))
                );
                final S3BlobStore.Operation operation = S3BlobStore.Operation.parse((String) metric.attributes().get("operation"));
                final S3BlobStore.StatsKey statsKey = new S3BlobStore.StatsKey(
                    operation,
                    OperationPurpose.parse((String) metric.attributes().get("purpose"))
                );
                assertThat(nodeName + "/" + statsKey + " exists", statsCollectors, hasKey(statsKey));
                assertThat(
                    nodeName + "/" + statsKey + " has correct sum",
                    metric.getLong(),
                    equalTo(statsCollectors.get(statsKey).requests.sum())
                );
                aggregatedMetrics.compute(statsKey, (k, v) -> v == null ? metric.getLong() : v + metric.getLong());
            });
        }

        // Metrics number should be consistent with server side request count as well.
        assertThat(aggregatedMetrics, equalTo(getServerMetrics()));
    }

    public void testRequestStatsWithOperationPurposes() throws IOException {
        final String repoName = createRepository(randomRepositoryName());
        final RepositoriesService repositoriesService = internalCluster().getCurrentMasterNodeInstance(RepositoriesService.class);
        final BlobStoreRepository repository = (BlobStoreRepository) repositoriesService.repository(repoName);
        final BlobStoreWrapper blobStore = asInstanceOf(BlobStoreWrapper.class, repository.blobStore());
        final S3BlobStore delegateBlobStore = asInstanceOf(S3BlobStore.class, blobStore.delegate());
        final S3BlobStore.StatsCollectors statsCollectors = delegateBlobStore.getStatsCollectors();

        // Initial stats are collected for repository verification, which counts as SNAPSHOT_METADATA
        final Set<String> allOperations = EnumSet.allOf(S3BlobStore.Operation.class)
            .stream()
            .map(S3BlobStore.Operation::getKey)
            .collect(Collectors.toUnmodifiableSet());
        assertThat(
            statsCollectors.collectors.keySet().stream().map(S3BlobStore.StatsKey::purpose).collect(Collectors.toUnmodifiableSet()),
            equalTo(Set.of(OperationPurpose.SNAPSHOT_METADATA))
        );
        final Map<String, BlobStoreActionStats> initialStats = blobStore.stats();
        assertThat(initialStats.keySet(), equalTo(allOperations));

        // Collect more stats with an operation purpose other than the default
        final OperationPurpose purpose = randomValueOtherThan(OperationPurpose.SNAPSHOT_METADATA, BlobStoreTestUtil::randomPurpose);
        final BlobPath blobPath = repository.basePath().add(randomAlphaOfLength(10));
        final BlobContainer blobContainer = blobStore.blobContainer(blobPath);
        final BytesArray whatToWrite = new BytesArray(randomByteArrayOfLength(randomIntBetween(100, 1000)));
        blobContainer.writeBlob(purpose, "test.txt", whatToWrite, true);
        try (InputStream is = blobContainer.readBlob(purpose, "test.txt")) {
            is.readAllBytes();
        }
        blobContainer.delete(purpose);

        // Internal stats collection is fine-grained and records different purposes
        assertThat(
            statsCollectors.collectors.keySet().stream().map(S3BlobStore.StatsKey::purpose).collect(Collectors.toUnmodifiableSet()),
            equalTo(Set.of(OperationPurpose.SNAPSHOT_METADATA, purpose))
        );
        // The stats report aggregates over different purposes
        final Map<String, BlobStoreActionStats> newStats = blobStore.stats();
        assertThat(newStats.keySet(), equalTo(allOperations));
        assertThat(newStats, not(equalTo(initialStats)));

        // Exercise stats report that keep find grained information
        final Map<String, BlobStoreActionStats> fineStats = statsCollectors.statsMap(true);
        assertThat(
            fineStats.keySet(),
            equalTo(
                statsCollectors.collectors.keySet().stream().map(S3BlobStore.StatsKey::toString).collect(Collectors.toUnmodifiableSet())
            )
        );
        // fine stats are equal to coarse grained stats (without entries with value 0) by aggregation
        assertThat(
            fineStats.entrySet()
                .stream()
                .collect(
                    Collectors.groupingBy(
                        entry -> entry.getKey().split("_", 2)[1],
                        Collectors.reducing(BlobStoreActionStats.ZERO, Map.Entry::getValue, BlobStoreActionStats::add)
                    )
                ),
            equalTo(
                newStats.entrySet()
                    .stream()
                    .filter(entry -> entry.getValue().isZero() == false)
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue))
            )
        );

        final Set<String> operationsSeenForTheNewPurpose = statsCollectors.collectors.keySet()
            .stream()
            .filter(sk -> sk.purpose() != OperationPurpose.SNAPSHOT_METADATA)
            .map(sk -> sk.operation().getKey())
            .collect(Collectors.toUnmodifiableSet());

        newStats.forEach((k, v) -> {
            if (operationsSeenForTheNewPurpose.contains(k)) {
                assertThat(newStats.get(k).requests(), greaterThan(initialStats.get(k).requests()));
                assertThat(newStats.get(k).operations(), greaterThan(initialStats.get(k).operations()));
            } else {
                assertThat(newStats.get(k), equalTo(initialStats.get(k)));
            }
        });
    }

    public void testEnforcedCooldownPeriod() throws IOException {
        final String repoName = randomRepositoryName();
        createRepository(
            repoName,
            Settings.builder().put(repositorySettings(repoName)).put(S3Repository.COOLDOWN_PERIOD.getKey(), TEST_COOLDOWN_PERIOD).build(),
            true
        );

        final SnapshotId fakeOldSnapshot = clusterAdmin().prepareCreateSnapshot(TEST_REQUEST_TIMEOUT, repoName, "snapshot-old")
            .setWaitForCompletion(true)
            .setIndices()
            .get()
            .getSnapshotInfo()
            .snapshotId();
        final RepositoriesService repositoriesService = internalCluster().getCurrentMasterNodeInstance(RepositoriesService.class);
        final BlobStoreRepository repository = (BlobStoreRepository) repositoriesService.repository(repoName);
        final RepositoryData repositoryData = getRepositoryData(repository);
        final RepositoryData modifiedRepositoryData = repositoryData.withoutUUIDs()
            .withExtraDetails(
                Collections.singletonMap(
                    fakeOldSnapshot,
                    new RepositoryData.SnapshotDetails(
                        SnapshotState.SUCCESS,
                        IndexVersion.fromId(6080099),   // minimum node version compatible with 7.6.0
                        0L, // -1 would refresh RepositoryData and find the real version
                        0L, // -1 would refresh RepositoryData and find the real version,
                        "" // null would refresh RepositoryData and find the real version
                    )
                )
            );
        final BytesReference serialized = BytesReference.bytes(
            modifiedRepositoryData.snapshotsToXContent(XContentFactory.jsonBuilder(), SnapshotsService.OLD_SNAPSHOT_FORMAT)
        );
        if (randomBoolean()) {
            repository.blobStore()
                .blobContainer(repository.basePath())
                .writeBlobAtomic(randomNonDataPurpose(), getRepositoryDataBlobName(modifiedRepositoryData.getGenId()), serialized, true);
        } else {
            repository.blobStore()
                .blobContainer(repository.basePath())
                .writeBlobAtomic(
                    randomNonDataPurpose(),
                    getRepositoryDataBlobName(modifiedRepositoryData.getGenId()),
                    serialized.streamInput(),
                    serialized.length(),
                    true
                );
        }

        final String newSnapshotName = "snapshot-new";
        final long beforeThrottledSnapshot = repository.threadPool().relativeTimeInNanos();
        clusterAdmin().prepareCreateSnapshot(TEST_REQUEST_TIMEOUT, repoName, newSnapshotName).setWaitForCompletion(true).setIndices().get();
        assertThat(repository.threadPool().relativeTimeInNanos() - beforeThrottledSnapshot, greaterThan(TEST_COOLDOWN_PERIOD.getNanos()));

        final long beforeThrottledDelete = repository.threadPool().relativeTimeInNanos();
        clusterAdmin().prepareDeleteSnapshot(TEST_REQUEST_TIMEOUT, repoName, newSnapshotName).get();
        assertThat(repository.threadPool().relativeTimeInNanos() - beforeThrottledDelete, greaterThan(TEST_COOLDOWN_PERIOD.getNanos()));

        final long beforeFastDelete = repository.threadPool().relativeTimeInNanos();
        clusterAdmin().prepareDeleteSnapshot(TEST_REQUEST_TIMEOUT, repoName, fakeOldSnapshot.getName()).get();
        assertThat(repository.threadPool().relativeTimeInNanos() - beforeFastDelete, lessThan(TEST_COOLDOWN_PERIOD.getNanos()));
    }

    private Map<S3BlobStore.StatsKey, Long> getServerMetrics() {
        for (HttpHandler h : handlers.values()) {
            while (h instanceof DelegatingHttpHandler) {
                if (h instanceof S3StatsCollectorHttpHandler s3StatsCollectorHttpHandler) {
                    return Maps.transformValues(s3StatsCollectorHttpHandler.getMetricsCount(), AtomicLong::get);
                }
                h = ((DelegatingHttpHandler) h).getDelegate();
            }
        }
        return Collections.emptyMap();
    }

    public void testMultipartUploadCleanup() {
        final String repoName = randomRepositoryName();
        createRepository(repoName, repositorySettings(repoName), true);

        createIndex("test-idx-1");
        for (int i = 0; i < 100; i++) {
            prepareIndex("test-idx-1").setId(Integer.toString(i)).setSource("foo", "bar" + i).get();
        }
        client().admin().indices().prepareRefresh().get();

        final String snapshotName = randomIdentifier();
        CreateSnapshotResponse createSnapshotResponse = clusterAdmin().prepareCreateSnapshot(TEST_REQUEST_TIMEOUT, repoName, snapshotName)
            .setWaitForCompletion(true)
            .get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards())
        );

        final var repository = asInstanceOf(
            S3Repository.class,
            internalCluster().getCurrentMasterNodeInstance(RepositoriesService.class).repository(repoName)
        );
        final var blobStore = asInstanceOf(S3BlobStore.class, asInstanceOf(BlobStoreWrapper.class, repository.blobStore()).delegate());

        try (var clientRef = blobStore.clientReference()) {
            final var danglingBlobName = randomIdentifier();
            final var initiateMultipartUploadRequest = CreateMultipartUploadRequest.builder()
                .bucket(blobStore.bucket())
                .key(blobStore.blobContainer(repository.basePath().add("test-multipart-upload")).path().buildAsString() + danglingBlobName)
                .overrideConfiguration(
                    AwsRequestOverrideConfiguration.builder()
                        .putRawQueryParameter(S3BlobStore.CUSTOM_QUERY_PARAMETER_PURPOSE, randomPurpose().getKey())
                        .build()
                )
                .build();

            final var multipartUploadResult = clientRef.client().createMultipartUpload(initiateMultipartUploadRequest);

            final var listMultipartUploadsRequest = ListMultipartUploadsRequest.builder()
                .bucket(blobStore.bucket())
                .prefix(repository.basePath().buildAsString())
                .overrideConfiguration(
                    AwsRequestOverrideConfiguration.builder()
                        .putRawQueryParameter(S3BlobStore.CUSTOM_QUERY_PARAMETER_PURPOSE, randomPurpose().getKey())
                        .build()
                )
                .build();
            assertEquals(
                List.of(multipartUploadResult.uploadId()),
                clientRef.client()
                    .listMultipartUploads(listMultipartUploadsRequest)
                    .uploads()
                    .stream()
                    .map(MultipartUpload::uploadId)
                    .toList()
            );

            final var seenCleanupLogLatch = new CountDownLatch(1);
            MockLog.assertThatLogger(() -> {
                assertAcked(clusterAdmin().prepareDeleteSnapshot(TEST_REQUEST_TIMEOUT, repoName, snapshotName));
                safeAwait(seenCleanupLogLatch);
            },
                S3BlobContainer.class,
                new MockLog.SeenEventExpectation(
                    "found-dangling",
                    S3BlobContainer.class.getCanonicalName(),
                    Level.INFO,
                    "found [1] possibly-dangling multipart uploads; will clean them up after finalizing the current snapshot deletions"
                ),
                new MockLog.SeenEventExpectation(
                    "cleaned-dangling",
                    S3BlobContainer.class.getCanonicalName(),
                    Level.INFO,
                    Strings.format(
                        "cleaned up dangling multipart upload [%s] of blob [%s]*test-multipart-upload/%s]",
                        multipartUploadResult.uploadId(),
                        repoName,
                        danglingBlobName
                    )
                ) {
                    @Override
                    public void match(LogEvent event) {
                        super.match(event);
                        if (Regex.simpleMatch(message, event.getMessage().getFormattedMessage())) {
                            seenCleanupLogLatch.countDown();
                        }
                    }
                }
            );

            assertThat(
                clientRef.client()
                    .listMultipartUploads(listMultipartUploadsRequest)
                    .uploads()
                    .stream()
                    .map(MultipartUpload::uploadId)
                    .toList(),
                empty()
            );
        }
    }

    /**
     * S3RepositoryPlugin that allows to disable chunked encoding and to set a low threshold between single upload and multipart upload.
     */
    public static class TestS3RepositoryPlugin extends S3RepositoryPlugin {

        public TestS3RepositoryPlugin(final Settings settings) {
            super(settings);
        }

        @Override
        public List<Setting<?>> getSettings() {
            final List<Setting<?>> settings = new ArrayList<>(super.getSettings());
            settings.add(S3ClientSettings.DISABLE_CHUNKED_ENCODING);
            return settings;
        }

        @Override
        protected S3Repository createRepository(
            ProjectId projectId,
            RepositoryMetadata metadata,
            NamedXContentRegistry registry,
            ClusterService clusterService,
            BigArrays bigArrays,
            RecoverySettings recoverySettings,
            S3RepositoriesMetrics s3RepositoriesMetrics
        ) {
            return new S3Repository(
                projectId,
                metadata,
                registry,
                getService(),
                clusterService,
                bigArrays,
                recoverySettings,
                s3RepositoriesMetrics
            ) {

                @Override
                public BlobStore blobStore() {
                    return new BlobStoreWrapper(super.blobStore()) {
                        @Override
                        public BlobContainer blobContainer(final BlobPath path) {
                            return new S3BlobContainer(path, (S3BlobStore) delegate()) {
                                @Override
                                long getLargeBlobThresholdInBytes() {
                                    return ByteSizeUnit.MB.toBytes(1L);
                                }

                                @Override
                                long getMaxCopySizeBeforeMultipart() {
                                    // on my laptop 10K exercises this better but larger values should be fine for nightlies
                                    return ByteSizeUnit.MB.toBytes(1L);
                                }

                                @Override
                                void ensureMultiPartUploadSize(long blobSize) {}
                            };
                        }
                    };
                }
            };
        }
    }

    @SuppressForbidden(reason = "this test uses a HttpHandler to emulate an S3 endpoint")
    protected class S3BlobStoreHttpHandler extends S3HttpHandler implements BlobStoreHttpHandler {

        S3BlobStoreHttpHandler(final String bucket) {
            super(bucket);
        }

        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            assertTrue(
                isValidAwsV4SignedAuthorizationHeader(
                    "test_access_key",
                    // If unset, the region used by the SDK is usually going to be `us-east-1` but sometimes these tests run on bare EC2
                    // machines and the SDK picks up the region from the IMDS there, so for now we use '*' to skip validation.
                    Objects.requireNonNullElse(region, "*"),
                    "s3",
                    exchange.getRequestHeaders().getFirst("Authorization")
                )
            );
            super.handle(exchange);
        }
    }

    /**
     * HTTP handler that injects random S3 service errors
     *
     * Note: it is not a good idea to allow this handler to simulate too many errors as it would
     * slow down the test suite.
     */
    @SuppressForbidden(reason = "this test uses a HttpServer to emulate an S3 endpoint")
    protected static class S3ErroneousHttpHandler extends ErroneousHttpHandler {

        // S3 SDK stops retrying after TOKEN_BUCKET_SIZE/DEFAULT_EXCEPTION_TOKEN_COST == 500/5 == 100 failures in quick succession
        // see software.amazon.awssdk.retries.DefaultRetryStrategy.Legacy.TOKEN_BUCKET_SIZE
        // see software.amazon.awssdk.retries.DefaultRetryStrategy.Legacy.DEFAULT_EXCEPTION_TOKEN_COST
        private final Semaphore failurePermits = new Semaphore(99);

        S3ErroneousHttpHandler(final HttpHandler delegate, final int maxErrorsPerRequest) {
            super(delegate, maxErrorsPerRequest);
        }

        /**
         * Bypasses {@link ErroneousHttpHandler#handle} once we exhaust {@link #failurePermits} because S3 will start rate limiting.
         */
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (failurePermits.tryAcquire()) {
                super.handle(exchange);
            } else {
                delegate.handle(exchange);
            }
        }

        @Override
        protected String requestUniqueId(final HttpExchange exchange) {
            // Amazon SDK client provides a unique ID per request
            return exchange.getRequestHeaders().getFirst(ApplyTransactionIdStage.HEADER_SDK_TRANSACTION_ID);
        }
    }

    /**
     * HTTP handler that tracks the number of requests performed against S3.
     */
    @SuppressForbidden(reason = "this test uses a HttpServer to emulate an S3 endpoint")
    protected class S3StatsCollectorHttpHandler extends HttpStatsCollectorHandler {

        private final Map<S3BlobStore.StatsKey, AtomicLong> metricsCount = ConcurrentCollections.newConcurrentMap();

        S3StatsCollectorHttpHandler(final HttpHandler delegate) {
            super(delegate, Arrays.stream(S3BlobStore.Operation.values()).map(S3BlobStore.Operation::getKey).toArray(String[]::new));
        }

        private S3HttpHandler.S3Request parseRequest(HttpExchange exchange) {
            return new S3HttpHandler("bucket").parseRequest(exchange);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            final S3HttpHandler.S3Request s3Request = parseRequest(exchange);
            if ("HEAD".equals(s3Request.method())) {
                assertTrue(s3Request.hasQueryParamOnce(S3BlobStore.CUSTOM_QUERY_PARAMETER_PURPOSE));
            }
            if (shouldFailCompleteMultipartUploadRequest.get() && s3Request.isCompleteMultipartUploadRequest()) {
                trackRequest("PutMultipartObject");
                try (exchange) {
                    drainInputStream(exchange.getRequestBody());
                    exchange.sendResponseHeaders(
                        randomFrom(RestStatus.BAD_REQUEST.getStatus(), RestStatus.TOO_MANY_REQUESTS.getStatus()),
                        -1
                    );
                    return;
                }
            }
            super.handle(exchange);
        }

        @Override
        public void maybeTrack(HttpExchange exchange) {
            final S3HttpHandler.S3Request request = parseRequest(exchange);
            final OperationPurpose purpose = OperationPurpose.parse(request.getQueryParamOnce(S3BlobStore.CUSTOM_QUERY_PARAMETER_PURPOSE));
            if (request.isListObjectsRequest()) {
                trackRequest("ListObjects");
                metricsCount.computeIfAbsent(new S3BlobStore.StatsKey(S3BlobStore.Operation.LIST_OBJECTS, purpose), k -> new AtomicLong())
                    .incrementAndGet();
            } else if (request.isListMultipartUploadsRequest()) {
                // TODO track ListMultipartUploads requests
                logger.info("--> ListMultipartUploads not tracked [{}] with parsed purpose [{}]", request, purpose.getKey());
            } else if (request.isGetObjectRequest()) {
                trackRequest("GetObject");
                metricsCount.computeIfAbsent(new S3BlobStore.StatsKey(S3BlobStore.Operation.GET_OBJECT, purpose), k -> new AtomicLong())
                    .incrementAndGet();
            } else if (isMultiPartUpload(request)) {
                trackRequest("PutMultipartObject");
                metricsCount.computeIfAbsent(
                    new S3BlobStore.StatsKey(S3BlobStore.Operation.PUT_MULTIPART_OBJECT, purpose),
                    k -> new AtomicLong()
                ).incrementAndGet();
            } else if (request.isPutObjectRequest()) {
                if (exchange.getRequestHeaders().containsKey(S3BlobStore.CUSTOM_QUERY_PARAMETER_COPY_SOURCE)) {
                    trackRequest("CopyObject");
                    metricsCount.computeIfAbsent(
                        new S3BlobStore.StatsKey(S3BlobStore.Operation.COPY_OBJECT, purpose),
                        k -> new AtomicLong()
                    ).incrementAndGet();
                } else {
                    trackRequest("PutObject");
                    metricsCount.computeIfAbsent(new S3BlobStore.StatsKey(S3BlobStore.Operation.PUT_OBJECT, purpose), k -> new AtomicLong())
                        .incrementAndGet();
                }
            } else if (request.isMultiObjectDeleteRequest()) {
                trackRequest("DeleteObjects");
                metricsCount.computeIfAbsent(new S3BlobStore.StatsKey(S3BlobStore.Operation.DELETE_OBJECTS, purpose), k -> new AtomicLong())
                    .incrementAndGet();
            } else if (request.isAbortMultipartUploadRequest()) {
                trackRequest("AbortMultipartObject");
                metricsCount.computeIfAbsent(
                    new S3BlobStore.StatsKey(S3BlobStore.Operation.ABORT_MULTIPART_OBJECT, purpose),
                    k -> new AtomicLong()
                ).incrementAndGet();
            } else if (request.isHeadObjectRequest()) {
                trackRequest("HeadObject");
                metricsCount.computeIfAbsent(new S3BlobStore.StatsKey(S3BlobStore.Operation.HEAD_OBJECT, purpose), k -> new AtomicLong())
                    .incrementAndGet();
            } else {
                logger.info("--> rawRequest not tracked [{}] with parsed purpose [{}]", request, purpose.getKey());
            }
        }

        Map<S3BlobStore.StatsKey, AtomicLong> getMetricsCount() {
            return metricsCount;
        }

        private boolean isMultiPartUpload(S3HttpHandler.S3Request s3Request) {
            return s3Request.isInitiateMultipartUploadRequest()
                || s3Request.isUploadPartRequest()
                || s3Request.isCompleteMultipartUploadRequest();
        }
    }
}
