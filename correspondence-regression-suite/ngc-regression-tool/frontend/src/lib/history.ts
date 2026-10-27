import type { CaseDetail } from './types';

function canonical(text: string): string {
  const sort = (v: unknown): unknown =>
    Array.isArray(v) ? v.map(sort) : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v as object).sort().map((k) => [k, sort((v as Record<string, unknown>)[k])])) : v;
  try {
    return JSON.stringify(sort(JSON.parse(text)));
  } catch {
    return text;
  }
}

const list = (csv: string | null): string => (csv ? csv.split(',').join(', ') : 'none');

/** Plain-English list of what differs between two revisions of a test case (read-only history). */
export function revisionChanges(previous: CaseDetail | undefined, current: CaseDetail): string[] {
  if (!previous) return ['Created'];
  const out: string[] = [];
  if (previous.name !== current.name) out.push(`Name: “${previous.name}” → “${current.name}”`);
  if ((previous.description ?? '') !== (current.description ?? '')) out.push('Description changed');
  if (previous.kafka_kind !== current.kafka_kind || previous.kafka_topic_pattern !== current.kafka_topic_pattern) {
    out.push(`Topic: ${previous.kafka_topic_pattern} → ${current.kafka_topic_pattern}`);
  }
  if (previous.event_type !== current.event_type) out.push(`Event type: ${previous.event_type} → ${current.event_type}`);
  if (previous.carrier !== current.carrier) out.push(`Carrier: ${previous.carrier} → ${current.carrier}`);
  if ((previous.plan_codes ?? '') !== (current.plan_codes ?? '')) out.push(`Plan codes: ${list(previous.plan_codes)} → ${list(current.plan_codes)}`);
  if ((previous.tags ?? '') !== (current.tags ?? '')) out.push(`Tags: ${list(previous.tags)} → ${list(current.tags)}`);
  if (previous.enabled !== current.enabled) out.push(current.enabled ? 'Enabled' : 'Disabled');
  if (previous.timeout_seconds !== current.timeout_seconds) out.push(`Wait time: ${previous.timeout_seconds}s → ${current.timeout_seconds}s`);
  if (previous.payload_artifact_id !== current.payload_artifact_id) out.push('Event payload replaced');
  if (previous.golden_artifact_id !== current.golden_artifact_id) out.push(current.golden_artifact_id ? 'Golden XML replaced' : 'Golden XML removed');
  if (canonical(previous.expected_json) !== canonical(current.expected_json)) out.push('Expected outcome changed');
  return out.length ? out : ['No visible change'];
}
