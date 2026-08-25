import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class MilvusQaIdValidator {
    private static final int BATCH_SIZE = 100;

    private MilvusQaIdValidator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected: <host> <port> <database> <collection> <idsCsv>");
        }
        String host = requireIdentifier(args[0], "host", true);
        int port = parsePort(args[1]);
        String database = requireIdentifier(args[2], "database", false);
        String collection = requireIdentifier(args[3], "collection", false);
        List<Long> requested = parseIds(args[4]);

        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build());
        try {
            client.useDatabase(database);
            client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
            Set<Long> found = new LinkedHashSet<>();
            for (int from = 0; from < requested.size(); from += BATCH_SIZE) {
                List<Long> batch = requested.subList(from, Math.min(requested.size(), from + BATCH_SIZE));
                String filter = "qa_pair_id in [" + batch.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(",")) + "]";
                QueryResp response = client.query(QueryReq.builder()
                        .collectionName(collection)
                        .filter(filter)
                        .outputFields(List.of("qa_pair_id"))
                        .limit(batch.size())
                        .build());
                if (response == null || response.getQueryResults() == null) {
                    continue;
                }
                response.getQueryResults().forEach(result -> {
                    Object value = result.getEntity().get("qa_pair_id");
                    if (value instanceof Number number) {
                        found.add(number.longValue());
                    } else if (value != null) {
                        found.add(Long.parseLong(value.toString()));
                    }
                });
            }
            List<Long> missing = requested.stream().filter(id -> !found.contains(id)).toList();
            System.out.printf("MILVUS_ID_RESULT requested=%d found=%d missing=%d%n",
                    requested.size(), found.size(), missing.size());
            System.out.println("MILVUS_MISSING_IDS=" + missing.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",")));
            if (!missing.isEmpty()) {
                System.exit(2);
            }
        } finally {
            client.close();
        }
    }

    private static List<Long> parseIds(String csv) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid qa_pair_id: " + value, exception);
            }
            if (id <= 0) {
                throw new IllegalArgumentException("qa_pair_id must be positive: " + id);
            }
            ids.add(id);
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("At least one qa_pair_id is required");
        }
        return new ArrayList<>(ids);
    }

    private static String requireIdentifier(String value, String name, boolean allowDotsAndHyphens) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String pattern = allowDotsAndHyphens ? "[A-Za-z0-9.-]+" : "[A-Za-z0-9_]+";
        if (!value.matches(pattern)) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return value;
    }

    private static int parsePort(String value) {
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        return port;
    }
}
