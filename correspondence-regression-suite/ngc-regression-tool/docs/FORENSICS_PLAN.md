# Event Forensics & Health — High-Level Plan

## Goal
When an event fails, look back at past executions and see one event's full history (per carrier or global) with traceable facts. Let users review health from recent and past events.

## Scope
- **Now:** dev/QA, inside this tool (new "Forensics" mode).
- **Later:** PROD, alerting, roles/audit log, retention.
- History is open to every logged-in user.
- Policy number is shown in full. No names, addresses, SSN or raw payloads/XML bodies.

## Reference standards
- OpenTelemetry messaging/Kafka conventions: the Kafka key is not a unique ID.
- Temporal-style model: one ordered history per execution plus a searchable index on business identifiers.
- Golden signals (RED/USE) for health: traffic, errors, latency, saturation.
- CCM vendors (Newgen, Baker Goodchild, Kyber/Lob): delivery dashboards and audit logs.

## What NGC gives us
- Anchor is `incoming_events.uniqueId`. Children join by `originalUniqueId` or `uniqueId`.
- `correlationId` is not unique: a replay creates a new `uniqueId` under the same one.
- Best timeline sources: `target_role_and_event_status_audit` and `event_journey`.

## Known gaps in NGC data
- Early rejections (dead letter, invalid, filtered) leave no `incoming_events` row.
- Status is overwritten on retry and stale-recovery resets.
- Only the last `incoming_event_execution_sequence` attempt is kept.
- `technical_failures.workerName` is set only for some workers.
- Missing indexes (see Phase 1 findings).

## Phases
| Phase | Deliverable | Status |
|---|---|---|
| 1 | Mongo hardening: timeouts and `maxTime`, read-only privilege check, index inventory (`GET /api/health/indexes`, Health page card) | Done |
| 2 | Event Search (correlationId, uniqueId, policyNumber, carrier, eventType, bounded window) and History page: joined timeline, failure reasons, "history lost" markers, copyable fact sheet | Done, awaiting review |
| 3 | Health scoped by carrier and event type, stage latency p50/p90, snapshot rollups | |
| 4 | Tool-owned event ledger in H2 to keep statuses NGC overwrites, plus replay/retry lineage view | |

### Phase 1 — changes made
- **Client:** connect, socket and server-selection timeouts and `secondaryPreferred` reads, set under `ngc.mongo.*` in `application.yml`.
- **Query limits:** a 15s `maxTime` on every query in the health and per-event collectors, through one helper (`MongoLimits`). Timeouts now return "database took too long, a missing index is the usual cause".
- **Diagnostics:** new `GET /api/health/indexes` reports whether the Mongo user is read-only and which indexes our queries need but the collections lack, with document counts. It never creates anything.
- **Startup check:** a warning is logged if the Mongo user can write.
- **UI:** new "Database access and indexes" card on the Health page.
- **Tests:** 14 new backend tests (193 total) and 4 new frontend tests (52 total), plus a read-only live test.

### Phase 2 — changes made
- **Browse tree:** Forensics page with a window selector and a carrier typeahead. It drills carrier, then event type, then outcome (technical, business, stuck, OK), then the event list, then the history. The selection lives in the URL.
- **Jump to:** exact `correlationId`, `uniqueId` or policy number, with no time window.
- **Counts:** `GET /api/forensics/tree` builds carrier and event-type counts from one windowed aggregate, plus dead letters and invalid requests per carrier.
- **Search:** `GET /api/forensics/events` filters by carrier, event type, outcome, window and exact ids, 50 per page with "load more". It also lists events rejected before processing.
- **History:** `GET /api/forensics/events/{correlationId}` joins 11 collections into one timeline with stage states, retries and "history lost" markers. A correlation id with several attempts shows the latest attempt by default, with a switcher and an "all attempts" option.
- **Safety:** allow-listed projections only, never message bodies. Error text is sanitized (stack frames dropped, emails, SSNs, card numbers and personal key/values masked). Rows are capped at 200 per collection and 500 timeline entries, and a marker says when that happens.
- **UI:** "Copy fact sheet" copies the ids, stages, retries and timeline as plain text.
- **Tests:** 28 new backend tests and 13 new frontend tests, plus a read-only live test. A scratch instance on port 8086 was checked end to end.

### Phase 2 findings (dev)
- One correlation id had 200 or more processing attempts (about 1,700 timeline rows), so the history is scoped to one attempt by default.
- Callbacks (`internal_events`) cannot be tied to one attempt, so they show for every attempt, and a marker says so.
- Mixed business-and-technical failures count in both outcome buckets.
- Searches by `correlationId`, `policyNumber` and `createdAt` still scan `incoming_events` until the index request from Phase 1 is done.

## Phase 1 findings (dev)
- The Mongo user can write (`insert`, `update`, `remove`, `createIndex`, `dropCollection`, ...), so the tool is read-only only by its own code.
- `incoming_events` has no index on `correlationId`, `policyNumber` or `createdAt`. `cds_events` and `technical_failures` have only `_id`. `incoming_event_execution_sequence` has only `_id`.

## Action items
- Ask the NGC/DBA team for a read-only Mongo user for this tool, and for indexes on `incoming_events` (`correlationId`, `policyNumber`, `createdAt`), `cds_events`, `technical_failures` and `incoming_event_execution_sequence` before Phase 2 searches run on larger data.

## Verification
- Unit tests for the timeline builder and the sanitizer.
- Live read-only test on dev with a failed event and a replay.
- Vitest page tests for Search and History.
