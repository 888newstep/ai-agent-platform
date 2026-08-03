# Testing and Observability

This repository now includes an open-source-friendly baseline for testing, coverage, and monitoring.

## Test commands

```bash
mvn test
mvn verify
```

- `mvn test`: runs unit tests and web-layer tests.
- `mvn verify`: runs the full validation lifecycle and generates JaCoCo coverage reports.
- `mvn verify` also enforces a minimum bundle line coverage of `35%` as the current baseline.

## Coverage artifacts

After `mvn verify`, coverage outputs are generated in:

- `target/site/jacoco/index.html`
- `target/site/jacoco/jacoco.xml`
- `target/jacoco.exec`

## Observability endpoints

- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`

## Custom business metrics

- `ai.chat.requests.total`
- `ai.chat.latency`
- `ai.rag.search.total`
- `ai.rag.search.latency`
- `ai.rag.results.count`
- `ai.document.ingestion.queued.total`
- `ai.document.ingestion.total`
- `ai.document.ingestion.latency`
- `ai.document.chunk.count`

## Local Prometheus + Grafana

```bash
docker compose up -d app prometheus grafana
```

Access URLs:

- App: `http://localhost:8081`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

Default Grafana credentials:

- Username: `admin`
- Password: `admin123456`

Optional overrides in `.env`:

```dotenv
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=change-me
```

Grafana auto-loads:

- Prometheus datasource
- `AI Agent Platform Overview` dashboard

## CI artifacts

The GitHub Actions workflow uploads:

- packaged application jar
- Surefire test reports
- JaCoCo coverage report

Current CI baseline:

- JaCoCo bundle line coverage must be at least `35%`

This makes it easier for contributors to inspect failures and quality signals directly from CI.
