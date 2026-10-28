# Bulk execution: plan

## Why
The first design assumed a tester runs a handful of events by hand. Real use is different: **schedules run whole modules
(hundreds to thousands of events) and most events are replayed to prove NGC still behaves**. Tags pick a quick subset;
a schedule runs everything it points at. So the engine, storage and UI must be built for bulk first.

## What was wrong for bulk
| Problem | Effect at 1,000 events |
|---|---|
| Every event was published at the same instant | Floods NGC (it caps ~80 workflows per pod), Dev Kafka and any real print/email path |
| Every event polled Mongo on its own (8 queries each, every 5 s) | ~1,600 queries/second against the NGC database |
| Run state lived only in memory | A restart left runs RUNNING forever |
| No cancel, no circuit breaker | A Kafka outage turned 1,000 cases into 1,000 separate errors, no way to stop a mistaken run |
| Run page loaded every case | Unusable UI, huge responses |
| Result = a list of pass/fail | A nightly run needs "what is newly broken since last night", not 940 greens |
| Per-event detail kept forever | ~20 rows per event per run, unbounded growth |
| Same-policy events could race | False failures when two transactions for one policy ran together |

**Scale target: about 1,000 events per run.** This is what the design and tests are sized for. Nothing in the code
enforces a maximum; pacing (`max-in-flight`, `events-per-second`) is what keeps larger runs safe.

## Design (phase 1, built)
1. **Throttled dispatch**: one orchestrator per run publishes at most `max-in-flight` unfinished events and at most
   `events-per-second`, with a global cap across runs. Both are set per run or per schedule (defaults in config).
2. **Batched collection**: one query per NGC collection per poll cycle for all in-flight events (`correlationId $in`),
   so cost no longer grows with run size.
3. **Per-policy ordering**: events for the same policy number never run at the same time.
4. **Restart-safe**: all state is in the database; on startup unfinished runs resume (published events keep waiting
   until their own deadline; unpublished ones are sent).
5. **Cancel and circuit breaker**: cancel stops dispatch and marks the rest skipped; 5 publish failures in a row abort
   the run instead of erroring every event.
6. **Large-run confirmation**: a manual run above the threshold (default 100) must confirm the event count. It is a prompt
   to prevent mistakes, not a limit; schedules never need to confirm.
   `POST /api/runs/preview` tells the UI how many events and roughly how long.
7. **Re-run failed**: one click creates a new run with only the failed/errored/timed-out events.
8. **Regression view**: each run is compared with the previous run of the same module and environment: new failures,
   fixed, still failing.
9. **Scalable run page**: counters, status filter, search and paging; live progress while running.
10. **Retention**: per-event detail (observations, assertions, XML copies) is purged after 30 days; run and event
    summaries are kept for a year.

## Phase 2 (needs decisions, not built)
- **Bulk import** of test cases (JSON/CSV plus payload files) so thousands of cases are not created one by one.
- **Baseline capture**: after a known-good run, "accept observed outcome" fills each case's expectation (final status,
  targets, CDS count, journey, XML golden) so bulk suites do not need hand-written expectations.
- **Replay real events** from NGC history into test cases.
