# Java runtime and connector contract

## Responsibilities

| Component | Implementation responsibility |
| --- | --- |
| CatalogLoader | Parse the versioned files strictly and preserve source locations |
| CapabilityRegistry | Register handlers by connector ID plus capability ID/version; reject duplicates |
| PlanCompiler | Resolve references, schemas, binding dependencies and suite policy into an immutable plan |
| ConnectorFactory | Load pinned, installed implementations through DI; never instantiate a scenario-supplied class |
| ScenarioRunner | Prepare fixtures, open selected sessions, await readiness, publish input and enforce deadlines |
| EvidenceStore | Append raw and normalized observations with origin, target identity, version and provenance |
| CapabilityHandler | Observe capability-specific evidence and evaluate the typed expectation |
| ReportWriter | Preserve expected/actual differences, achieved coverage, errors and cleanup status |

The catalog does not implement protocol clients or Java assertions. It selects registered behavior and configures expected values. Each handler advertises one exact contract and exposes concrete input, evidence and expectation classes. Jackson maps schema-validated documents to those Java types.

## Lifecycle

1. Load and validate the selected suite. Infer dependencies from evidence bindings and ensure capability prerequisites occur among a check's ancestors.
2. Resolve each selected service to an installed connector. Verify implementation and service contract versions, supported capability versions, required configuration, and dependency evidence modes. Unselected connectors need not be opened.
3. Provision scoped test data, stubs and simulated boundary drivers. Establish real delivery isolation. A configuration declaration alone is insufficient.
4. Open each active connector session and await observer readiness before publishing the source event. A downstream check may become eligible later, but its transport observations must already be buffered from before injection.
5. Publish once and retain the broker receipt. An ambiguous publish result must be reconciled before considering replay.
6. Resolve a check's literal inputs and typed bindings. Validate the complete input against its capability schema. Validate parameter values during compilation; constraints on deferred evidence values are rechecked here.
7. Observe evidence using the established session. Checks with resolved dependencies can progress concurrently. A check's deadline is its eligibility time plus `timeoutSeconds`, capped by the scenario's overall deadline. The overall deadline starts with run execution and includes setup.
8. Validate the evidence body against its schema, verify correlation and origin independently, then invoke `evaluate(inputs, observed, expected)`. The handler returns `PASSED` or structured `FAILED` mismatches. A schema-valid body alone is not a passing assertion.
9. Report all required outcomes and achieved provenance. Preserve diagnostics, then release only owned resources in a finally block and record cleanup failures.

Schemas describe supported behavior, not successful deployment. Missing handler, unbound config, unsupported PDF lookup or failed readiness produces `BLOCKED`/`ERROR` as appropriate, never `PASSED` or automatic depth reduction.

The Java SPI in `contracts/java/com/zinnia/ngc/regression/spi/Connector.java` is intentionally transport-neutral. `Session.awaitReady` enforces observation readiness. `observe` retrieves evidence; `evaluate` is pure and repeatable. A lifecycle simulator is a separately provisioned boundary driver, not a side effect of an assertion. It emits callbacks only after observing the correlated request on an isolated route.

## Capability semantics

| Capability | Java assertions required |
| --- | --- |
| `ngc.letter.prepared@1.0.0` | Match current source and recipient, retrieve the target XML, validate the approved client XSD, and compare policy number and target role |
| `cds.request.published@1.0.0` | Observe actual Kafka publication; compare current target identity and XML reference to bound inputs, then compare `published` and `sender` expectations |
| `tango.lifecycle.completed@1.0.0` | Correlate job and target to the bound request, verify completion event type and prove NGC processed that callback; retain REAL/SIMULATED origin |
| `tango.pdf.generated@1.0.0` | Resolve the authoritative job for the bound request and target, retrieve the current PDF, verify `generated`, readability, `pageCount >= minPages`, and every `textContains` entry |

`textContains` v1 means case-sensitive substring matching on extracted text after converting CRLF to LF; no implicit whitespace removal, case folding or OCR. A scanned PDF without extractable required text fails this capability. Introduce a separately versioned capability if OCR or normalized matching is needed. PDF structural/readability failure is an assertion failure with diagnostics, not a successful empty document.

Failed or incomplete observation need not fabricate an object satisfying the successful evidence schema. Preserve partial/raw evidence separately and return a typed timeout, unavailable or invalid-evidence error. When the deadline expires with healthy observers and the expected milestone missing, the runner can report a product/scenario failure. An observer outage is an inconclusive infrastructure failure. Unexpected mismatches must not be retried until green.

## Correlation and provenance

Use the run's source event ID and correlation ID to establish NGC unique ID and target event identity. Bind downstream requests, job IDs and artifact identities to that chain. Match neither policy number alone nor the latest job/filename for a policy. Multi-target scenarios need a separate check chain for each recipient and assertions on unexpected additional recipients when the business contract requires an exact set.

The catalog's field names are normalized evidence contracts. NGC/CDS wire schemas may use other names. For example, `requestId` must be mapped to an actual correlated outgoing event identity by the CDS adapter. Do not assume the illustrative source fixture is a deployable NGC event schema.

For PDFs, `artifactIdentity` is an authoritative immutable version or a trusted current-job lookup receipt linking the artifact to the job. A hash shows content identity, but cannot by itself prove freshness or ownership. The handler must validate the job/target/artifact chain before reporting success. Actual Tango PDF location and lookup interfaces remain to be agreed with CDS/Tango owners; the NGC FSx resend path does not establish that location.

`Evidence.mode` comes from verified connector deployment and observed origin, not from a scenario payload flag. Cross-check it against capability support, scenario evidence policy and mandatory suite requirements. A simulated completion callback or simulator PDF can never satisfy `tango.pdf.generated@1.0.0`, whose supported evidence is REAL only. The harness should retain origin evidence and configuration fingerprints in its report.

## Configuration and rollout

`configRef` identifies a deployment-owned configuration object. Resolve endpoint locations, topic mappings, storage access and secret references there. No live endpoints or secrets are committed in these examples. Runtime validates configuration through the selected connector and checks pinned binary identity; the authoring tool deliberately cannot certify these external resources.

The initial catalog covers positive XML, publication, lifecycle and real PDF verification. Add negative or recovery capabilities with explicit contracts, such as bounded output absence or a declared downstream failure. Do not overload `pdf.generated` with `generated: false`: proving absence requires a healthy observer and an explicit observation window.

The service manifests declare prerequisites rather than hardcoding Tango depth inside the runner. A future independent existing-job inspection capability can accept a leased job ID without requiring NGC/CDS checks. The current generated-letter PDF contract requires the current-run CDS chain to protect against stale evidence.

An NGC engineer can first implement fake handlers to verify runner lifecycle and failure propagation, then real NGC/Kafka observers, the scoped mocking bridge, and the Tango lifecycle/PDF handler. Verify each adapter against captured, reviewed wire contracts. Use the existing in-process NGC mocks for their demonstrated scope; mock Kafka success does not prove broker delivery.
