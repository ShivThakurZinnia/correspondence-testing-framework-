# Connectors

## Problem and current state
Corro Regression reads and writes several external systems: NGC's MongoDB, an S3 bucket, Kafka, and (new) EDS through the API Gateway. Each connection used to be built where it was first needed (`MongoConfig` in `collector`, `S3XmlStore` in `xml`, `KafkaEventPublisher` in `publisher`) and three classes each read `ngc.mongo.*` themselves. There was no way to fetch a stored document, which the PDF checks need.

Constraints:
- Everything except Kafka is read-only, and the tool must say so.
- The pre-signed download URL is a credential: never logged, never sent the gateway headers.
- More connectors will come, so adding one must not touch the others.
- QA PDFs contain customer data: nothing is committed, and local copies follow the retention settings.

## Layout
```
connector/
  Connector.java            id() + check(): what the Health page lists
  ConnectorException.java   kind: UNAVAILABLE | NOT_FOUND | DENIED | TOO_LARGE
  ConnectorController.java  GET /api/connectors
  mongo/   MongoConfig, MongoLimits, MongoDiagnostics, DatabaseReport, MongoConnector
  s3/      ObjectStore (readBytes, read), S3ObjectStore, S3Location
  eds/     EdsClient (fetch / resolveDownloadUrl / download), EdsDocument
  kafka/   EventPublisher, KafkaEventPublisher
```
Consumers (`MongoNgcDataSource`, `MongoHealthSource`, `MongoForensicsSource`, `XmlVerifier`, `RunExecutor`) keep their domain logic and depend on the connector types. Analysis of a fetched document is not a connector's job: it will live in a separate package and receive bytes.

The EDS client is a read-only port of the Explorer's `EdsDownloadUrlClient` (download only, none of its update, replace or delete calls). It is copied rather than shared because the two repos have separate stacks and release cycles; extract a library when a third consumer appears.

## Adding a connector
1. Create `connector/<system>/` with a client bean that is enabled only when its settings are present (`@ConditionalOnExpression`).
2. Implement `Connector` on the bean. `check()` must be cheap and read-only; return `NOT_PROBED` when the only honest check is a real call.
3. Add the id to `ConnectorController.KNOWN` and a row to the README table.
4. Map failures to `ConnectorException` kinds and keep secrets and driver messages out of them.

## User flow
```mermaid
flowchart TD
    A[Open Health page] --> B[Connections card asks /api/connectors]
    B --> C{Each connector}
    C -->|Not configured| D[Shown as not configured]
    C -->|Configured, cheap check available| E[Ping Mongo / head S3 bucket]
    C -->|Configured, no safe probe| F[Shown as not probed]
    E -->|Answers| G[OK]
    E -->|Fails| H[Failed with exception type only]
```

## Fetching a document (used by the PDF checks that follow)
```mermaid
sequenceDiagram
    participant T as Check or run step
    participant M as Mongo connector
    participant E as EdsClient
    participant GW as API Gateway
    participant S as Pre-signed storage URL

    T->>M: incoming_events for correlationId
    M-->>T: targetRoleAndEventStatus[].edsDocumentId
    T->>E: fetch(documentId)
    E->>GW: POST /eds/downloadUrl {documentId, correlationId=new UUID}<br/>X-Application-Header, optional cookie
    GW-->>E: preSignedUrl (or 404 / 403 / no URL)
    E->>E: require https, never log the URL
    E->>S: GET preSignedUrl (no gateway headers)
    S-->>E: bytes (read at most ngc.eds.max-bytes + 1)
    E-->>T: EdsDocument(contentType, filename, bytes) or ConnectorException
```

## Open points
- That `edsDocumentId` is the PDF Tango produced is inferred from NGC downloading PDFs from EDS before TIFF conversion; it has not been traced end to end. `EdsClientLiveTest` (set `API_GATEWAY_BASE_URL` and `EDS_LIVE_DOCUMENT_ID`) checks one real document starts with `%PDF-`.
- Whether `downloadUrl` needs the gateway cookie from this tool's network is unknown (`CORRO_GATEWAY_COOKIE` is supported).
- `API_GATEWAY_BASE_URL` is not tied to `NGC_ENVIRONMENT` by code; the Health card shows the gateway host so a mismatch is visible.
- Next phases, not in this change: fetch by correlationId through an API, store the bytes as a run-case artifact, then the PDF analysis and QA-defined constraints.
