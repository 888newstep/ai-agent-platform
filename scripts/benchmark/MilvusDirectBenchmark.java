import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class MilvusDirectBenchmark {
    private static final List<String> OUTPUT_FIELDS = List.of(
            "id", "question", "answer", "qa_text", "qa_pair_id", "category", "ts");

    private MilvusDirectBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "Expected: <host> <port> <database> <collection> <durationSeconds> <threadsCsv>");
        }
        String host = requireText(args[0], "host");
        int port = parseRange(args[1], "port", 1, 65_535);
        String database = requireText(args[2], "database");
        String collection = requireText(args[3], "collection");
        int durationSeconds = parseRange(args[4], "durationSeconds", 1, 300);
        List<Integer> threadLevels = parseThreadLevels(args[5]);

        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build());
        try {
            client.useDatabase(database);
            client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
            List<Float> vector = loadExistingVector(client, collection);
            SearchReq request = SearchReq.builder()
                    .collectionName(collection)
                    .data(List.of(new FloatVec(vector)))
                    .annsField("embedding")
                    .metricType(IndexParam.MetricType.COSINE)
                    .topK(5)
                    .outputFields(OUTPUT_FIELDS)
                    .searchParams(Map.of("ef", 64))
                    .build();

            SearchResp warmup = client.search(request);
            requireResults(warmup);
            System.out.printf("DIRECT_PROBE_OK dimension=%d results=%d%n",
                    vector.size(), warmup.getSearchResults().get(0).size());
            for (int threads : threadLevels) {
                runStage(client, request, threads, durationSeconds);
            }
        } finally {
            client.close();
        }
    }

    private static List<Float> loadExistingVector(MilvusClientV2 client, String collection) {
        QueryResp response = client.query(QueryReq.builder()
                .collectionName(collection)
                .filter("qa_pair_id >= 0")
                .outputFields(List.of("embedding"))
                .limit(1)
                .build());
        if (response == null || response.getQueryResults() == null || response.getQueryResults().isEmpty()) {
            throw new IllegalStateException("Collection has no queryable vector");
        }
        Object raw = response.getQueryResults().get(0).getEntity().get("embedding");
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalStateException("Query did not return an embedding vector");
        }
        List<Float> vector = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Number number)) {
                throw new IllegalStateException("Embedding contains a non-numeric value");
            }
            vector.add(number.floatValue());
        }
        return vector;
    }

    private static void runStage(MilvusClientV2 client,
                                 SearchReq request,
                                 int threads,
                                 int durationSeconds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong deadlineNanos = new AtomicLong();
        LongAdder success = new LongAdder();
        LongAdder errors = new LongAdder();
        ConcurrentLinkedQueue<Long> latenciesMicros = new ConcurrentLinkedQueue<>();
        for (int index = 0; index < threads; index++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    while (System.nanoTime() < deadlineNanos.get()) {
                        long started = System.nanoTime();
                        try {
                            requireResults(client.search(request));
                            success.increment();
                            latenciesMicros.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started));
                        } catch (RuntimeException exception) {
                            errors.increment();
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    errors.increment();
                }
            });
        }
        if (!ready.await(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            throw new IllegalStateException("Benchmark workers did not become ready");
        }
        long stageStarted = System.nanoTime();
        deadlineNanos.set(stageStarted + TimeUnit.SECONDS.toNanos(durationSeconds));
        start.countDown();
        executor.shutdown();
        if (!executor.awaitTermination(durationSeconds + 30L, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            throw new IllegalStateException("Benchmark stage did not stop on time");
        }
        double elapsedSeconds = (System.nanoTime() - stageStarted) / 1_000_000_000.0;

        long[] sortedMicros = latenciesMicros.stream().mapToLong(Long::longValue).sorted().toArray();
        long succeeded = success.sum();
        long failed = errors.sum();
        System.out.printf(
                "DIRECT_STAGE threads=%d success=%d errors=%d rps=%.2f meanMs=%.2f p50Ms=%.2f p95Ms=%.2f p99Ms=%.2f maxMs=%.2f%n",
                threads,
                succeeded,
                failed,
                succeeded / elapsedSeconds,
                Arrays.stream(sortedMicros).average().orElse(0) / 1000.0,
                percentile(sortedMicros, 0.50) / 1000.0,
                percentile(sortedMicros, 0.95) / 1000.0,
                percentile(sortedMicros, 0.99) / 1000.0,
                sortedMicros.length == 0 ? 0 : sortedMicros[sortedMicros.length - 1] / 1000.0);
    }

    private static void requireResults(SearchResp response) {
        if (response == null || response.getSearchResults() == null || response.getSearchResults().isEmpty()) {
            throw new IllegalStateException("Milvus search returned no result set");
        }
    }

    private static List<Integer> parseThreadLevels(String value) {
        List<Integer> levels = Arrays.stream(requireText(value, "threadsCsv").split(","))
                .map(String::trim)
                .map(item -> parseRange(item, "threads", 1, 200))
                .distinct()
                .toList();
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("At least one thread level is required");
        }
        return levels;
    }

    private static int parseRange(String value, String name, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static long percentile(long[] sortedValues, double percentile) {
        if (sortedValues.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sortedValues.length) - 1;
        return sortedValues[Math.max(0, Math.min(index, sortedValues.length - 1))];
    }
}
