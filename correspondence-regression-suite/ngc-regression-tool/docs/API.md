# Backend API (current)

All JSON keys are lowercase snake_case. Every endpoint needs HTTP Basic login with the single fixed account:
`REGRESSION_USER` (default `admin`) and `REGRESSION_PASSWORD` (required; the service will not start without it). The logged-in name is recorded as author/approver/trigger. Replace with real auth later.

| Method + path | Purpose |
|---|---|
| `POST /api/payloads?name=` | Validate + store uploaded event JSON unmodified; returns `artifactId`, `payloadCorrelationId`, carrier, eventType, planCode |
| `POST /api/payloads/golden?name=` (Content-Type `application/xml`) | Store a golden XML (well-formed, DTDs rejected); returns `artifactId` to attach as `goldenArtifactId` on a test case |
| `PUT /api/modules/{module}/cases/{scenarioKey}` | Create/revise a test case; `planCodes` is a list (the UI takes them comma separated). Create/revise a test case. Unchanged content → `changed=false`, no new version. Any change → new revision + new module version |
| `POST /api/modules/versions/{id}/status` | DRAFT / APPROVED / RELEASE_GATE / RETIRED. Kept for the API; the UI no longer uses it and schedules do not depend on it |
| `GET /api/modules`, `/{module}/versions`, `/{module}/versions/{n}/cases` | Module versions (read-only history) and the exact scenario revisions in each |
| `GET /api/modules/{module}/cases/{key}/revisions` | Revision history of one test case (shown in the UI's History pop-up) |
| `GET /api/modules/{module}/tag-counts?versionNo=` | Test cases and events (one per plan code) behind every tag, plus totals; powers the schedule picker |
| `GET /api/reference/tags?category=`, `/environments`, `/topics?env=` | Pick-lists for forms |
| `POST /api/runs` `{module, versionNo?, env, tags?, trigger?}` | Start a run in the background (202 + `runKey`). Tags are ANDed, e.g. `client:GILI,event:policy-page`; no versionNo = latest version |
| `GET /api/health?windowHours=168&stuckMinutes=30` | Live health of the connected environment (read-only Mongo): stuck work, failure rate by event, retries, dead letters, CDS callback funnel, new event types, each with a level (ok/warn/bad) and a plain-English issue list. 20-second cache; 503 if Mongo is not configured, 502 if it cannot be read |
| `GET /api/health/history?metric=&hours=72` | Stored 15-minute snapshots (STUCK_INCOMING, FAILURE_RATE_24H, RETRIES_EXHAUSTED, DEAD_LETTER_PENDING, PDF_COMPLETED_RATE, ...) for trend lines |
| `GET /api/dashboard` | Tile per env x module (last result, active runs, 7-day pass rate), running now, recent runs, `upcoming` (next 10 enabled schedules, soonest first, with `executing` flag) |
| `GET/POST /api/schedules`, `PUT /api/schedules/{id}`, `DELETE /api/schedules/{id}`, `PUT /api/schedules/{id}/enabled` | Manage schedules. Edit/delete return 409 while the schedule has a queued or running run. Cron is 6-field (`0 0 6 * * *`), evaluated in the schedule's timezone; an edit re-bases the next fire time. Deleting keeps past runs (they retain `schedule_name`). Create/edit/delete are written to `audit_log` |
| `GET /api/runs?env&module&status&trigger&page&size` | Build history, newest first |
| `GET /api/runs/{runKey}` | Run header + one row per event played with stage progress |
| `GET /api/events/{correlationId}` | One event: assertions (failures first), ordered timeline, artifacts, progress |
| `GET /api/events?correlationId&scenario&status&env&tag` | Historic events across all runs |

Progress stages per event: Published to Kafka → NGC received → Sent to CDS → PDF started → PDF completed → Delivery update (derived from `run_observation`).

Known portability gap: dashboard/run SQL uses H2 `DATEADD/DATEDIFF` and the module-case query uses `LISTAGG`; revisit when moving to Postgres.

## Run flow (per test case)
1. `RunService` pins the module version, selects enabled cases by tag, and registers a fresh UUID correlationId per case (status PENDING).
2. `RunExecutor` checks the carrier against `regression.allowed-carriers`, resolves the topic for the env, rewrites the uploaded payload's id, stores the injected payload, publishes (acks=all) and records partition/offset.
3. It polls NGC Mongo every `regression.poll-interval-ms`, storing new observations, until the event is settled (terminal status and all expected journey/callback names seen) or the case timeout passes.
4. `AssertionEngine` evaluates `expected_json`: incomingEvent.status, exact target set (status, deliveryType, emailNotification, agentType, documentType, xmlFileName present, tangoJobIdPresent), cds published/count, journey steps, callbacks (`a|b` = either), noFailures. `xml` fails until the S3 collector exists.
5. Case ends PASSED / FAILED / TIMEOUT (not settled) / ERROR (guard or infrastructure). Run is ERROR if any case errored, FAILED if any failed or timed out, else PASSED.

Requires `KAFKA_BOOTSTRAP_SERVER` (+ `KAFKA_KEY`/`KAFKA_SECRET`) and `MONGODB_URI`/`MONGODB_DATABASE`; without them a run ends in ERROR with a clear message.

## XML checks (`expected_json.xml`)
```json
"xml": { "role": "OWNER", "exists": true, "xsdValid": true, "goldenCompare": true,
         "ignore": ["CREATED", "POLICIES/POLICY/POL_ISSUE_DATE"] }
```
- `role` is required only when the event has several targets. Each key is optional; every key present must be satisfied.
- Location: the exact `OutputFilePath` NGC sent to CDS (from `cds_events`); falls back to `<s3FileBaseLocation>/<xmlFileName>` in `S3_BUCKET_NAME`. Both tried locations are listed when the XML is not found.
- `xsdValid` fetches the XSD named by the CDS payload's `XSDFilePath` (and any `xs:include`d files beside it) from S3 and validates.
- `goldenCompare` diffs against the test case's golden XML ignoring whitespace, comments and the listed element names or path suffixes. Failure messages list XPaths and difference types, never the values.
- The fetched XML is kept with the run as a `RUN_XML` artifact (role `GENERATED_XML`).
- `cds.fields` assertions read the CDS key/values (keys matched ignoring case; `Key~` = contains). Party name and id are never stored.
- Needs `S3_BUCKET_NAME`, `AWS_REGION` and AWS credentials from the default chain (e.g. `AWS_PROFILE` after `aws sso login`). Without S3 configured, a test with an `xml` section fails with a clear message.

## Environment wiring
`NGC_ENVIRONMENT` (e.g. `dev`) names the one environment that `MONGODB_URI`, Kafka and S3 point at. Only that environment is offered in the UI, and runs or schedules for any other are refused ("This instance is connected to dev, not qa"). To add QA later, run a second instance with QA's credentials and `NGC_ENVIRONMENT=qa`.

Health thresholds: stuck = PROCESSING/NEW_EVENT older than 30 min but younger than 7 days (older leftovers are shown separately and do not drive the level); failure rate warns at 10% and alarms at 25% once at least 5 events arrived; any retry that gave up warns (5+ alarms); dead letters warn, and alarm when the oldest has waited over 6 hours; a low PDF-completed rate only ever warns.

## Plan codes
A test case can list several plan codes (`planCodes`, max 50; letters, digits, `.`, `-`, `_`, up to 30 characters; repeats ignored ignoring case; order kept). Every plan code is played as its own event: the same uploaded payload with its `PLAN_CODE` identifier set to that code (added if the payload has none), its own fresh correlationId, and the same expected outcome. A run's `total_cases` counts events, so 3 plan codes in 2 test cases = 6 events. A test case with no plan code plays once with the payload as uploaded. Reordering plan codes does not create a new version; adding or removing one does. Only the `PLAN_CODE` identifier is changed; a plan code embedded elsewhere in the payload (for example inside `data`) is not rewritten.

## Bulk runs and schedules
Built for about 1,000 events per run; nothing enforces a maximum. See `docs/BULK_EXECUTION.md` for the reasoning.

| Endpoint | Purpose |
|---|---|
| `POST /api/runs/preview` | `{module, env, tags, versionNo?, eventsPerSecond?}` -> `{cases, events, minimumSeconds, confirmAbove, requiresConfirmation}` |
| `POST /api/runs` | Adds optional `maxInFlight`, `eventsPerSecond` and `confirmEvents`. A manual run above `regression.run.large-run-threshold` (100) must send `confirmEvents` equal to its event count; schedules never need to |
| `POST /api/runs/{key}/cancel` | Stops publishing; unsent and in-flight events become SKIPPED, run becomes CANCELLED |
| `POST /api/runs/{key}/rerun-failed` | New run containing only the failed, timed-out or errored events |
| `GET /api/runs/{key}?status=&q=&page=&size=` | Header, counters by status (`summary`), and one page of events, failures first. `status` is comma separated; `q` matches scenario, plan code or correlation id |
| `GET /api/runs/{key}/compare` | Against the previous finished run of the same module and environment: `new_failures`, `fixed`, `still_failing`, `new_cases`, plus the events |

How a run executes: one orchestrator per run publishes at most `max-in-flight` (25) unfinished events and `events-per-second` (5), under a global cap of `global-max-in-flight` (50); every poll cycle reads NGC with one batched query per collection; events of the same policy **and plan code** never overlap; five publish failures in a row abort the run; state lives in the database so unfinished runs resume after a restart (published events keep their own deadline; an event interrupted mid-publish is marked ERROR). Detail rows (observations, assertions, stored XML, injected payloads) are purged after `retention.detail-days` (30); run and event results after `summary-days` (365).

Tag selection (`tags`, schedules and runs): tags of the **same kind are alternatives** (`mapper:anniversary,mapper:new-business` = either), **different kinds must all match** (`client:GILI,mapper:anniversary` = GILI anniversary cases). Schedules always run the module's latest test cases; the optional `maxInFlight` / `eventsPerSecond` on a schedule apply to every run it starts.

Starter content: on startup `ModuleSeeder` fills any module that has no test cases yet (Policy Page, Anniversary, New Business, Policy) with test cases built from what Dev shows, with placeholder `REPLACE_ME` payloads to edit. It never touches a module that already has work. Disable with `regression.seed-modules=false`.

## Mapper groups
The 11 NGC XML mappers are seeded as plain MAPPER tags (name and Java class only). **The database stores no event names**: the tester decides, when creating a test case, which mapper group it belongs to (the editor requires one), and schedules and runs then pick test cases by group. `GET /api/modules/{module}/tag-counts` reports `no_mapper_cases` / `no_mapper_events` for test cases nobody grouped; the schedule picker and the module page flag them, because such a case only runs when no mapper is selected.

Reference only (not stored): NGC decides the mapper class per event from the rule engine's `TargetEventClassification`, and its `src/main/resources/business_rules/mappings/*-rule-mappings.json` list events per rule category. Neither is copied into this tool.
