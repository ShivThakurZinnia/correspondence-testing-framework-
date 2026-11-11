# Corro Regression Suite

Black-box regression tool for the nextgen-correspondence-service (NGC): injects real Kafka events, then checks what
NGC did in MongoDB, S3 and the CDS/Tango callbacks. Plan: `docs/PLAN.md`. Schema: `docs/SCHEMA.md`. API: `docs/API.md`.

## Run it
```bash
cp .env.example .env            # fill in REGRESSION_PASSWORD, MONGODB_URI + MONGODB_DATABASE, KAFKA_BOOTSTRAP_SERVER, S3_BUCKET_NAME
                                # (never commit .env; Kafka key/secret are only needed for Confluent Cloud)
(cd frontend && npm install && npm run build)     # builds the UI into backend/src/main/resources/static
(cd backend && mvn package)                       # JDK 21
java -jar backend/target/ngc-regression-0.1.0-SNAPSHOT.jar      # http://localhost:8085
```
Sign in with `REGRESSION_USER` (default `admin`) and `REGRESSION_PASSWORD`.

UI development: `cd frontend && npm run dev` (http://localhost:5173, proxies `/api` to 8085).

## CLI (same jar)
```bash
export REGRESSION_PASSWORD=...
java -jar ngc-regression.jar run --module policy-page --env dev --tags mapper:anniversary,mapper:policy-page [--yes] [--max-in-flight 25] [--rate 5]
java -jar ngc-regression.jar status <runKey>
java -jar ngc-regression.jar cancel <runKey>
java -jar ngc-regression.jar rerun-failed <runKey>
java -jar ngc-regression.jar runs --env dev
```

## Connectors
Everything that talks to another system lives in `backend/src/main/java/com/zinnia/regression/connector/`, one subpackage per system, and each bean implements `Connector` so the Health page can show it. Plan and diagrams: `docs/CONNECTORS_PLAN.md`.

| Package | System | Access | Settings |
|---|---|---|---|
| `connector/mongo` | NGC MongoDB | read-only, every query bounded by `maxTime` | `MONGODB_URI`, `MONGODB_DATABASE`, `ngc.mongo.*` |
| `connector/s3` | S3 bucket with generated XML | read-only (`ObjectStore`) | `S3_BUCKET_NAME`, `AWS_REGION`, AWS default credential chain |
| `connector/eds` | EDS documents through the API Gateway | read-only download (`EdsClient`) | `API_GATEWAY_BASE_URL`, `CORRO_APPLICATION_HEADER`, `CORRO_GATEWAY_COOKIE`, `ngc.eds.*` |
| `connector/kafka` | Kafka | write (event injection), allowed carriers only | `KAFKA_BOOTSTRAP_SERVER`, `KAFKA_KEY`, `KAFKA_SECRET` |

A connector stays off until its settings are present. To add one, create `connector/<system>/`, implement `Connector` on its bean, add its id to `ConnectorController.KNOWN`, and document it in the table above.

## Tests
`cd backend && mvn test` (JDK 21). Live smoke tests run only when their variables are set: read-only Mongo checks with `MONGODB_URI` + `MONGODB_DATABASE`, a publish-and-read-back check on a throwaway topic with `KAFKA_BOOTSTRAP_SERVER`, and one real EDS download with `API_GATEWAY_BASE_URL` + `EDS_LIVE_DOCUMENT_ID`.
`cd frontend && npm test`

## Safety
QA really sends print and email. Only carriers in `regression.allowed-carriers` (default `GILI`) can be injected; use synthetic
policies and recipients only.

## Mongo limits

Every query to the NGC database carries a `maxTime`, and the client has connect, socket and server-selection timeouts and reads from a secondary when one is available. Tune them under `ngc.mongo.*` in `application.yml` (`connect-timeout-ms`, `socket-timeout-ms`, `server-selection-timeout-ms`, `max-time-ms`, `read-preference`). Keep `socket-timeout-ms` above `max-time-ms` so the server aborts a slow query before the socket does. The Health page shows whether the user is read-only and which indexes are missing.
