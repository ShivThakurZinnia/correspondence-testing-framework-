export type RunStatus = 'QUEUED' | 'RUNNING' | 'PASSED' | 'FAILED' | 'ERROR' | 'CANCELLED';
export type CaseStatus = 'PENDING' | 'RUNNING' | 'PASSED' | 'FAILED' | 'ERROR' | 'TIMEOUT' | 'SKIPPED';

export interface RunRow {
  run_key: string;
  status: RunStatus;
  trigger_source: string;
  triggered_by: string | null;
  env: string;
  module: string | null;
  module_version: string | null;
  tag_expression: string | null;
  queued_at: string;
  started_at: string | null;
  finished_at: string | null;
  total_cases: number;
  passed_cases: number;
  failed_cases: number;
  ngc_build_version: string | null;
  rule_set_version: string | null;
  schedule_name: string | null;
  duration_seconds: number | null;
}

export interface Stage {
  code: string;
  label: string;
  reached: boolean;
}

export interface CaseRow {
  run_case_id: number;
  correlation_id: string;
  ngc_unique_id: string | null;
  status: CaseStatus;
  final_incoming_status: string | null;
  failure_summary: string | null;
  topic_name: string;
  published_at: string | null;
  started_at: string | null;
  finished_at: string | null;
  scenario_key: string;
  scenario_name: string;
  revision: number;
  carrier: string;
  event_type: string;
  plan_code: string | null;
  run_key: string;
  env: string;
  progress?: Stage[];
}

export interface RunDetail extends Omit<RunRow, 'schedule_name' | 'duration_seconds'> {
  max_in_flight: number | null;
  events_per_second: number | null;
  cancel_requested: boolean;
  details_purged: boolean;
  rerun_of: string | null;
  summary: Record<CaseStatus, number>;
  page: number;
  size: number;
  matching: number;
  cases: CaseRow[];
}

export interface CompareEvent {
  scenario_key: string;
  plan_code: string | null;
  status: CaseStatus;
  previous_status: CaseStatus;
  correlation_id: string;
}

export interface RunCompare {
  previous: { run_key: string; finished_at: string } | null;
  new_failures?: number;
  fixed?: number;
  still_failing?: number;
  new_cases?: number;
  new_failure_events?: CompareEvent[];
  fixed_events?: CompareEvent[];
}

export interface RunPreview {
  cases: number;
  events: number;
  minimumSeconds: number;
  confirmAbove: number;
  requiresConfirmation: boolean;
}

export interface Assertion {
  category: string;
  path: string;
  expected: string | null;
  actual: string | null;
  passed: boolean;
  message: string | null;
}

export interface TimelineRow {
  occurred_at: string;
  source: string;
  collection: string | null;
  event_type: string | null;
  status: string | null;
  target_role: string | null;
  tango_job_id: string | null;
  role_tracking_id: string | null;
}

export interface EventDetail extends CaseRow {
  assertions: Assertion[];
  timeline: TimelineRow[];
  artifacts: { id: number; kind: string; name: string; role: string }[];
}

export interface Tile {
  env: string;
  scope: string | null;
  last_run_key: string | null;
  last_status: RunStatus | null;
  active_runs: number;
  runs_7d: number;
  passed_7d: number;
}

export interface Upcoming {
  id: number;
  name: string;
  env: string;
  tag_expression: string | null;
  cron_expression: string;
  timezone: string;
  next_fire_at: string;
  last_fired_at: string | null;
  executing: boolean;
}

export interface Dashboard {
  tiles: Tile[];
  running: RunRow[];
  recent: RunRow[];
  upcoming: Upcoming[];
}

export interface ModuleRow {
  code: string;
  name: string;
  description: string | null;
}

export interface ModuleVersion {
  id: number;
  version_no: number;
  version: string;
  status: 'DRAFT' | 'APPROVED' | 'RELEASE_GATE' | 'RETIRED';
  change_note: string;
  created_by: string;
  created_at: string;
  case_count: number;
}

export interface VersionCase {
  /** Present in the all-modules list. */
  module?: string;
  scenario_key: string;
  revision: number;
  name: string;
  kafka_kind: 'SOR' | 'BPM';
  kafka_topic_pattern: string;
  event_type: string;
  carrier: string;
  plan_codes: string | null;
  enabled: boolean;
  expected_json: string;
  tags: string | null;
}

export interface CaseDetail extends VersionCase {
  description: string | null;
  payload_artifact_id: number;
  golden_artifact_id: number | null;
  timeout_seconds: number;
  change_note: string | null;
  created_by: string;
  created_at: string;
  payload_correlation_id: string | null;
}

export interface DiffRow {
  scenario_key: string;
  change: 'ADDED' | 'REMOVED' | 'CHANGED';
  from_revision: string | null;
  to_revision: string | null;
}

export interface Tag {
  category: 'CLIENT' | 'EVENT' | 'MAPPER' | 'PRIORITY' | 'PATH';
  code: string;
  label: string;
  description?: string | null;
}

export interface Topic {
  env: string;
  kind: 'SOR' | 'BPM';
  topic_name: string;
}

export interface Schedule {
  id: number;
  name: string;
  module: string | null;
  pinned_version_no: number | null;
  env: string;
  tag_expression: string | null;
  cron_expression: string;
  timezone: string;
  overlap_policy: 'SKIP' | 'QUEUE';
  enabled: boolean;
  max_in_flight: number | null;
  events_per_second: number | null;
  last_fired_at: string | null;
  next_fire_at: string | null;
  executing: boolean;
}

export interface ScheduleInput {
  name: string;
  module: string | null;
  versionNo: number | null;
  env: string;
  tags: string | null;
  cron: string;
  timezone: string;
  overlapPolicy: 'SKIP' | 'QUEUE';
  maxInFlight: number | null;
  eventsPerSecond: number | null;
}

export interface PayloadUpload {
  valid: boolean;
  errors: string[];
  artifactId?: number;
  payloadCorrelationId?: string;
  carrier?: string;
  eventType?: string;
  planCode?: string | null;
}

export interface SaveResult {
  testCaseId: number;
  testCaseVersionId: number;
  revision: number;
  changed: boolean;
  moduleVersionId: number;
  moduleVersion: string;
}

export interface Artifact {
  id: number;
  kind: string;
  name: string;
  content_type: string;
  size_bytes: number;
  content: string;
}

export type Level = 'ok' | 'warn' | 'bad' | 'info';

export interface HealthIssue {
  level: Level;
  text: string;
}

export interface HealthByEvent {
  carrier: string;
  event_type: string;
  total: number;
  failed: number;
  technical: number;
  business: number;
  sent_to_cds: number;
  processed: number;
  failure_rate: number | null;
}

export interface HealthReport {
  generated_at: string;
  window_hours: number;
  level: Level;
  last_event_at: string | null;
  events_in_window: number;
  issues: HealthIssue[];
  stuck: {
    level: Level;
    threshold_minutes: number;
    incoming_stuck: number;
    oldest_incoming_minutes: number | null;
    incoming_stale: number;
    internal_stuck: number;
    timed_overdue_recent: number;
    timed_overdue_older: number;
  };
  failures: { level: Level; total: number; failed: number; technical: number; business: number; failure_rate: number | null; by_event: HealthByEvent[] };
  workers: { name: string; count: number }[];
  retries: { level: Level; successful: number; exhausted: number; retrying: number };
  dead_letters: { level: Level; pending: number; exhausted: number; replayed: number; oldest_pending_minutes: number | null };
  funnel: {
    level: Level;
    sent_to_cds: number;
    pdf_started: number;
    pdf_completed: number;
    delivery: number;
    pdf_started_rate: number | null;
    pdf_completed_rate: number | null;
    median_seconds_to_pdf_started: number | null;
    p90_seconds_to_pdf_started: number | null;
  };
  new_event_types: { carrier: string; event_type: string; first_seen: string }[];
}

export interface HealthResponse {
  environment: string;
  report: HealthReport;
}

export interface HistoryPoint {
  captured_at: string;
  metric_value: number;
}

export interface TagCount {
  category: Tag['category'];
  code: string;
  label: string;
  description: string | null;
  cases: number;
  events: number;
}

export interface TagCounts {
  version_no: number | null;
  version: string | null;
  status: string | null;
  total_cases: number;
  total_events: number;
  no_mapper_cases: number;
  no_mapper_events: number;
  tags: TagCount[];
}

export interface Revision extends CaseDetail {}
