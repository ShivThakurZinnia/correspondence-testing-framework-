# NGC Regression Tool (NGC-6194 / NGC-6703) — Plan

## Context
NGC shares orchestration across clients; a change can pass unit tests yet break a client's route, target role, XML, delivery path or status. We need a **black-box regression tool** that injects real Kafka events into live QA NGC and asserts observable outcomes: Mongo state, S3 XML, outgoing CDS message, CDS/Tango callbacks, final statuses.

Decisions (user): new repo; **Java 21 (Spring Boot) backend + React frontend**; small tool; Mongo connection URL supplied via the tool's `.env`; **start with GILI Policy Pages in QA**; live QA first (QA really sends Print/Email; CDS then returns the email-sent / delivery-completed events); CLI now, MCP later; one central instance stores results from all runs/surfaces; scheduled daily runs (one or several times, per env); versioned test modules (every change = new version).

## Observed in QA Mongo (read-only, structure only; env=dev DB from NGC `.env`)
GILI `policy.activation.completed` (source `zahara`, `ZSOR_CONSUMER`, plans e.g. GCFIA902/903/905):
- `incoming_events`: one target `OWNER`, `documentType=POLPG`, `deliveryType=PRINT`, `emailNotification` YES/NO, `agentType=PRIMARYSERVICINGAGENT`, `xmlFileName`, `applicationImagePath`; target status `SENT_TO_CDS`; **no `tangoJobId` on the target** in sampled docs.
- Journey sequence: `requestValid`, `requestStart`, `requestSentToCDS(OWNER)`; later `pdf.generation.started` (CDS callback). Callbacks land in `internal_events` (`pdf.generation.started/completed`, `delivery.sent.print`, `delivery.completed`, `eds.upload.completed`, `needs.review`).
- Status distribution for this event: SENT_TO_CDS 137, TECHNICAL_FAILURE 71, PROCESSING 44, BUSINESS_FAILURE 15, FAILED 9; **no `PROCESSED` seen** → parent often stays `SENT_TO_CDS`. Expected values must be baselined from real QA behavior, not assumed (Jira's `finalStatus: SENT_TO_CDS` fits). **`SENT_TO_DP` is legacy/old data and is ignored** (never an expected status; the tool flags it as unexpected if it ever appears).
- Same `correlationId` can be replayed → repeated `cds_events`/journey rows; the tool must always use a fresh correlationId and filter by it (and by `uniqueId`).
- `internal_events` can carry `TECHNICAL_FAILURE` for `delivery.completed`/`needs.review`: assert on it explicitly.
Next read-only step (Phase 0): sample one full GILI happy-path chain incl. `message` field shape of `internal_events` and `cds_events.message` (CDS XML key/values) to build fixtures — redact policy numbers/names.

## Existing utilities (checked) — none verifies the flow end to end
- `/Users/SansarMor/code/bulk` (`bulk_player.py`, `correlation_id_manager.py`, `diversity_generator.py`, `event_category_mapping.json`): replays/generates event payloads with fresh correlation IDs → **reuse its event-diversity/category mapping and payload ideas for the injector**; it asserts nothing.
- `/Users/SansarMor/code/scripts` (`bulk-message-producer.sh`, Confluent CLI): load-test producer only.
- `/Users/SansarMor/code/payload-uploader` (Java): replays extracted payloads to the API gateway (`extracted_payloads_farm1*.json` = real-shaped payloads usable as fixture seeds after masking); no assertions.
- `/Users/SansarMor/code/corro_xml_comparison` (Java, `results/`): Go-vs-Java XML comparison → **candidate for the golden-XML diff component**.
- NGC Cucumber suite (in-JVM, XML-only checks), `rules-local-shim` (rule outputs), `nextgen-correspondence-scheduler` (dead-letter replay + polling; not a test scheduler, but its Mongo/schedule patterns are a model).
Conclusion: no tool injects → waits → asserts Mongo/S3/CDS/callbacks. Gap confirmed; build it, reusing the pieces above.

## Collections to watch (QA Mongo, all read-only; "smart" progress signals)
Per-run (by fresh `correlationId`): 
- `incoming_events` (parent status, `targetRoleAndEventStatus[]`, `s3FileBaseLocation`, `edsDocumentFileDetails`), `cds_events` (CDS payload sent, count must equal targets — duplicates flag resend), `event_journey` (ordered NGC + CDS lifecycle; also catches `requestInvalid`/`requestTerminated`), `internal_events` (CDS callbacks with own status PROCESSED/TECHNICAL_FAILURE; `targetRole`, `documentType`), `target_role_and_event_status_audit` (per-target status transitions over time → timeline for UI), `rule_execution_results` (which rule outputs drove routing; good for diagnosing failures), `incoming_event_execution_sequence` (PASSED/FAILED per-step), `incoming_events_xml_validation_result` (XML validation pass/fail rule lists + ruleVersion), `timed_corro` (reminder staging: status UNPROCESSED/COMPLETED/REJECTED, `followupDateTime`), `zl_event_journey` (ZL publish status), `technical_failures` (worker + error), `technical_failure_retries` (attempt/status), `dead_letter_events` (gatekeeper failures: PENDING_REPLAY…), `incoming_events_from_data_processor` (only legacy flow; ignore).
Environment health signals (dashboard "keep a tap on progress", not per-run): 
- Stuck work: `incoming_events` `PROCESSING` older than 30 min (38 right now), internal_events `PROCESSING`, `timed_corro` UNPROCESSED past `followupDateTime`.
- Failure rate per event/plan/worker: `technical_failures.workerName` top offenders (e.g. SearchDocumentWorker, UploadFilesOnSFTPWorker, CarrierDetailsWorker), `incoming_events` TECHNICAL_FAILURE/BUSINESS_FAILURE ratios by carrier+eventType, `internal_events` TECHNICAL_FAILURE (e.g. `delivery.completed`, `needs.review`).
- Retry health: `technical_failure_retries` MAX_ATTEMPTS_EXCEEDED vs RETRY_SUCCESSFUL; `dead_letter_events` backlog.
- Funnel/latency: time from `requestValid` → `requestSentToCDS` → `pdf.generation.completed` → `delivery.*` per carrier/plan (from `event_journey`/`internal_events` timestamps); % reaching each stage; duplicate `cds_events` per correlationId.
- Drift detection: new eventTypes/targetRoles/statuses never seen before, and XML validation rule failures by `ruleVersion`.
These feed a "Health" page and also become assertions (e.g. "no new stuck docs", "funnel conversion ≥ baseline") and scheduled-run history trends.

## Architecture (Java + React, small)
```
React UI  ─┐
CLI (jar)  ├─> Spring Boot "regression-service" (single deployable, one central instance)
MCP (later)┘        |- ScenarioRepo (files in git, module versions)
                    |- Runner: Kafka injector -> poller -> collectors -> assertion engine
                    |- Scheduler (Spring @Scheduled/Quartz, cron+tz+env)
                    |- AppStore: **H2 (file mode, e.g. ./data/ngc-regression)** via Spring Data JPA + Flyway migrations (portable SQL so a later move to Postgres is a config change); holds test cases, module versions, tags/reference data (client/event/mapper), uploaded payloads, schedules, runs, results, assertion details; large artifacts (XML, CDS payload, snapshots) as files on disk under ./data/artifacts (path in DB), S3 later
                    |- NGC Mongo is read-only for the tool (collectors only); the tool never writes to NGC DB
                    '- Collectors: Mongo (incoming_events, cds_events, event_journey, internal_events, technical_failures, dead_letter_events), S3 (XML), Kafka (optional CDS topic)
```
Config via `.env` (same convention as NGC `DotenvLoader`): `MONGODB_URI` (tester supplies; NGC QA db to read, with `MONGODB_DATABASE`), Kafka bootstrap/key/secret (host is reachable), AWS bucket/creds, H2 file path, per-env profiles. Secrets never shown in UI/logs. Results/config live in H2 (decided), not in the NGC DB. H2 file DB means one central instance must host it (single writer); migration target to be decided later.
CLI example: `java -jar ngc-regression.jar run --module policy-page --env qa --tag gili` — talks to the central service so results are always stored centrally.

## Versioned test modules (policy-page is the first)
A module = scenarios + fixtures + golden XML/CDS payload expectations + assertion config. **Any change creates a new immutable version** (`policy-page@1.4.0` + content hash + author + change note + timestamp). UI edits create a new version; old versions stay runnable and viewable; diff between versions; each run pins module version + records NGC/rule versions observed; schedules pin a version or follow `latest`; states draft → approved → release-gate. A failure right after a version change is flagged "expectation changed" vs "NGC regressed".

## Scheduler
Per schedule: module, version|latest, scenario filter (priority/tag), env, cron + timezone (one fixed time or several/day, different per env), overlap guard, notify on failure. Editable in UI. Runs from the scheduler, UI, CLI and MCP all land in the same ResultStore.

## Topics and tags (per user)
- **Topic is mandatory per test case; tester must choose it** (no default, validation fails if missing): `zinnia.correspondence.sor` for SOR events (NGC `zsor` consumer, `ZSOR_CONSUMER`) and `zinnia.correspondence.<env>` for BPM events (NGC `bpm` consumer, e.g. `zinnia.correspondence.dev`; env-resolved per target env). UI offers a dropdown of the topics configured per env; tool checks the topic exists/is reachable before running.
- **Tags are first-class** on every scenario and filterable in UI/CLI/scheduler/MCP: `client:<GILI>`, `event:<policy-page>`, `mapper:<policy|anniversary|new-business|…>`, plus `priority:<MVP|release-gate|extended>`, `path:<print|email|…>`. Run selection is a tag expression, e.g. `--tags client:GILI,event:policy-page` or `mapper:anniversary`; schedules and dashboards group/trend by tag; results store the tag set of each run.
- **Reference data in DB:** tables `client`, `event`, `mapper` (plus priority/path) are stored in H2 and **seeded on first start** (Flyway seed: clients e.g. GILI; events e.g. policy-page, new-business, anniversary; mappers e.g. policy, anniversary, new-business — final list to confirm). Test-case forms use these as pick-lists; validation rejects unknown tags. **CRUD for these is a later UI phase** (initially seed + read-only API); changes to reference data are audited.
- **Event payload is uploaded by the tester** when defining a test case (the Kafka event JSON, i.e. the `EventModel`): upload/paste in UI, `--payload file.json` in CLI. Tool validates it parses as `EventModel` (required `correlationid`, `source`, `carrier`, `type`, `identifiers`), stores it versioned with the test case, and the runner patches in a fresh `correlationid` (marker prefix) and synthetic identifiers at run time; warns if payload looks like real PII.

## Scenario model (tester defines product, input, expected)
```yaml
scenarioId: gili-policy-page-owner-print
tags: [client:GILI, event:policy-page, mapper:policy, priority:MVP, path:print]
client: GILI
eventType: policy.activation.completed
topic: zinnia.correspondence.sor     # REQUIRED, tester-chosen; or zinnia.correspondence.<env> for BPM
product: { planCode: GCFIA903, policyNumber: <synthetic> }
input: <uploaded by tester, stored with this test-case version>   # runner patches correlationid, identifiers
expected:
  incomingEvent: { status: SENT_TO_CDS }
  targets: [ { role: OWNER, status: SENT_TO_CDS, deliveryType: PRINT, emailNotification: YES|NO, agentType: PRIMARYSERVICINGAGENT, documentType: POLPG, xmlFileName: present } ]
  xml: { exists: true, xsdValid: true, golden: expected/gili-happy.xml, ignore: [timestamps] }
  cds: { published: true, fields: { ClientCode, ProductCode, TargetPartyRole, TargetDeliveryType, DocumentType, OutputFilePath~, XSDFilePath~ } }
  journey: [requestValid, requestStart, requestSentToCDS]
  callbacks: [pdf.generation.started, pdf.generation.completed, delivery.sent.print|delivery.sent.email, delivery.completed.email]   # from internal_events/event_journey
  noFailures: [technical_failures, dead_letter_events]
  timeouts: { total: 10m }
```
Multi-party/agent-CC/email expectations come from the rule repo (`/Users/SansarMor/code/correspondence-ruleengine-artifact`): `TargetEventAndRoleValidation` (roles), `TargetRoleDeliveryType` (deliveryType, deliveryEmailNotification, ccRoleInfo/agentType), `CDSFlow` (CDS vs SFTP/Merrill), run via `tools/rules-local-shim` to *propose* expectations testers review (DMN hit policy is FIRST — evaluate, don't read rows). Later phase.

## Safety
QA sends real Print/Email: runner enforces allow-list of GILI test policies/plan codes and synthetic recipient data; fixtures contain no real PII/PHI; never store raw `message`/policyholder fields in reports unmasked; cleanup/retention by marker prefix; dry-run default for cleanup. **Open:** confirm who receives QA print/email and that test addresses are safe. Org note: QA data may contain client data; this chat env isn't BAA-covered — queries so far projected only structural fields.

## UI (React) — key screens
Dashboard (latest runs per env/module, pass rate trend, red/green), Run detail (per-scenario timeline: Kafka in → NGC status → XML → CDS → callbacks, with expected vs actual diff and artifact links), Scenario/module editor (form + YAML, validate, save as new version), Version history/diff, Schedules, Environments/config check (connectivity health, no secrets), "Run now".

## Project home
`/Users/SansarMor/code/service/regression-suite` (new git repo). First action after plan approval (plan mode only allows editing this plan file): create that directory and save this plan there as `docs/PLAN.md`, plus `.env.example` (no real values) and `.gitignore` (`.env`, `data/`, `target/`, `node_modules/`). Layout: `backend/` (Spring Boot, Java 21, Maven), `frontend/` (React), `cli` as a subcommand of the backend jar, `docs/`, `modules/` (versioned test-module exports).

## Phases
0. Finish flow discovery in QA Mongo; create repo skeleton; safety rules; diagrams.
1. Core + ResultStore + CLI: scenario parser, Kafka injector, correlation tracker, poller/timeouts, Mongo collectors, assertions (status/targets/CDS/journey), report. GILI policy-page happy path end to end.
2. S3 XML collector, XSD validation, golden diff (port logic from NGC `integrationTests/util/XmlComparisonHelper`), negative scenarios (rule-rejected, XML/business failure, missing callback).
3. React UI + module versioning + scheduler + notifications.
4. More GILI policy-page variants (plans, email Y/N, agent), then other events/clients; rule-derived matrix.
5. MCP server (list/run/report) wrapping the same service API; CI trigger; bug-to-test governance.

## Reuse
NGC fixtures `src/test/resources/integrationTests/**/events/*.json`; `XmlComparisonHelper`; enums/models (`EventLifeCycleStatus`, `CorrespondenceJourneyEventType`, `TargetRoleAndEventStatus`, `EventModel`, `BuildOutgoingEventToCDS` for CDS payload keys); `DotenvLoader` pattern; rule repo shim. Not reusable: Cucumber in-JVM steps.

## Verification
- Run GILI happy path in QA with synthetic policy: report shows targets/XML/CDS/callbacks asserted, stored centrally, visible in UI and via CLI.
- Mutation test: change an expected value/golden → fails with correlationId + artifact links; new module version created on every edit; old version still runnable.
- Schedule two runs (different envs/times) and confirm they fire and are stored; overlap guard works.
- Replay a past prod bug as a scenario to prove it detects the old failure.

## Open questions
1. (Decided) H2 file DB first; revisit migration target (e.g. Postgres) later.
2. Safe GILI test policies/plan codes and recipients for real QA print/email.
3. Kafka access from the tool's host to `zinnia.correspondence.sor` / `zinnia.correspondence.<env>` in QA (topic is tester-chosen per test).
4. Confirm initial seed list for client / event / mapper names (CRUD UI comes later).
