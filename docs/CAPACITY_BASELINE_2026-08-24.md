# Capacity Baseline - 2026-08-24

## Test topology

- Application and JMeter: local Windows 11.
- MySQL and Redis: local Windows 11.
- Milvus: configured cloud host on port `19530`, database `cs_agent`, collection `ecommerce_qa`.
- Milvus mode: QA schema, read-only, 1024-dimensional vectors, COSINE, `ef=64`, Top-K 5.
- RabbitMQ was not involved because it is not part of the application request path.

## Application HTTP results

| Scenario | Threads | Throughput | P95 | Error rate |
|---|---:|---:|---:|---:|
| HTTP health | 10 | 10,811 RPS | 1 ms | 0% |
| MySQL/Redis actuator health | 5 | 347 RPS | 21 ms | 0% |
| MySQL/Redis actuator health | 20 | 392 RPS | 60 ms | 0% |
| MySQL/Redis actuator health | 50 | 236 RPS | 272 ms | 0% |

The MySQL/Redis health path saturates between 20 and 50 concurrent requests. Increasing concurrency beyond 20 reduced throughput and sharply increased latency.

## End-to-end knowledge search

These short smoke stages include the external embedding API, Redis request protection, application processing, network transit, and Milvus search. They validate the complete path but do not isolate Milvus capacity.

| Threads | Samples | Throughput | Mean | P95 | P99 | Error rate |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 54 | 6.16 RPS | 164 ms | 286 ms | 315 ms | 0% |
| 2 | 124 | 14.22 RPS | 141 ms | 170 ms | 181 ms | 0% |
| 5 | 229 | 25.56 RPS | 170 ms | 278 ms | 635 ms | 0% |

The initial probe took 2.66 seconds because of cold initialization. A later warm probe took 152 ms. Use five concurrent requests as the initial application-level load-test ceiling until a longer, budgeted embedding-provider test is performed.

## Direct Milvus results

The direct benchmark reads one existing vector and reuses it for concurrent, read-only gRPC searches. It does not call an embedding model.

| Threads | Throughput | Mean | P95 | P99 | Errors |
|---|---:|---:|---:|---:|---:|
| 1 | 35.67 RPS | 28.02 ms | 39.57 ms | 122.65 ms | 0 |
| 5 | 202.34 RPS | 24.70 ms | 32.51 ms | 39.00 ms | 0 |
| 10 | 405.74 RPS | 24.63 ms | 33.43 ms | 40.51 ms | 0 |
| 20 | 783.62 RPS | 25.48 ms | 34.55 ms | 49.10 ms | 0 |
| 50 | 914.28 RPS | 54.45 ms | 79.12 ms | 92.54 ms | 0 |
| 75 | 924.99 RPS | 80.89 ms | 105.79 ms | 145.46 ms | 0 |
| 100 | 887.72 RPS | 111.87 ms | 144.99 ms | 163.66 ms | 0 |

The throughput plateau begins around 50 concurrent searches. At 100 threads throughput decreases while P95 rises, so 100 concurrent direct searches are beyond the efficient operating range.

## Initial gates

- Direct Milvus gate at 50 threads: zero errors, at least 800 RPS, P95 no more than 120 ms.
- Application search smoke at 5 threads: zero errors. Latency gates should be finalized after a 60-second run with an approved embedding API budget.
- MySQL/Redis actuator path: use 20 threads as the current upper baseline; do not increase pools solely to improve the health endpoint result.

Run the repeatable direct gate with:

```powershell
.\scripts\run-milvus-capacity.ps1 `
  -MilvusHost '<cloud-milvus-host>' `
  -Database cs_agent `
  -Collection ecommerce_qa `
  -ThreadLevels '1,5,10,20,50' `
  -GateThreads 50 `
  -MinGateRps 800 `
  -MaxGateP95Milliseconds 120
```

Results are environment-specific. Re-run after changing the collection, index parameters, cloud host size, network route, Milvus version, or output fields.
