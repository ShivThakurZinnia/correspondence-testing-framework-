import type { Tone } from './format';
import type { ForensicsHistory } from './types';

export const WINDOWS = [
  { label: 'Last hour', hours: 1 },
  { label: 'Last 24 hours', hours: 24 },
  { label: 'Last 3 days', hours: 72 },
  { label: 'Last 7 days', hours: 168 },
  { label: 'Last 30 days', hours: 720 },
  { label: 'Last 90 days', hours: 2160 },
];

export const OUTCOME_LABEL: Record<string, string> = {
  any: 'All events',
  failed: 'All failures',
  technical: 'Technical failure',
  business: 'Business failure',
  stuck: 'Stuck (processing)',
  ok: 'Sent / OK',
};

export function ngcTone(status: string | null | undefined): Tone {
  switch (status) {
    case 'SENT_TO_CDS':
    case 'SENT_TO_MERRILL':
    case 'SENT_TO_EMAIL_SERVICE':
    case 'PROCESSED':
    case 'PASSED':
    case 'PASS':
    case 'SENT':
    case 'RETRY_SUCCESSFUL':
    case 'REPLAY_SUCCESSFUL':
      return 'ok';
    case 'FAILED':
    case 'FAIL':
    case 'TECHNICAL_FAILURE':
    case 'BUSINESS_FAILURE':
    case 'MAX_ATTEMPTS_EXCEEDED':
      return 'bad';
    case 'PROCESSING':
    case 'NEW_EVENT':
    case 'RECEIVED':
      return 'run';
    default:
      return status && /^(FAILED_|RETRY_ATTEMPT|PENDING)/.test(status) ? 'warn' : 'idle';
  }
}

export const params = (values: Record<string, string | undefined>): URLSearchParams => {
  const q = new URLSearchParams();
  Object.entries(values).forEach(([k, v]) => v && q.set(k, v));
  return q;
};

const mark = { done: '✓', failed: '✗', pending: '…' } as const;

export function factSheet(h: ForensicsHistory, when: Date = new Date()): string {
  const latest = [...h.attempts].reverse().find((a) => a.unique_id === h.selected_attempt) ?? h.attempts[h.attempts.length - 1];
  const lines = [`Event history for correlationId ${h.correlation_id}`, `Generated ${when.toISOString()}`];
  if (latest) {
    lines.push(
      `Carrier ${latest.carrier ?? '-'} | Event ${latest.event_type ?? '-'} | Policy ${latest.policy_number ?? '-'} | Plan ${latest.plan_code ?? '-'}`,
      `Attempt ${latest.unique_id} created ${latest.created_at ?? '-'}`,
      `Status ${latest.status ?? '-'}${latest.error_category ? ` (${latest.error_category})` : ''}${latest.error ? `: ${latest.error}` : ''}`,
    );
    latest.targets.forEach((t) => lines.push(`  Target ${t.role ?? '-'}: ${t.status ?? '-'}${t.delivery_type ? ` via ${t.delivery_type}` : ''}${t.error ? ` - ${t.error}` : ''}`));
  }
  lines.push(`Attempts sharing this correlationId: ${h.attempt_count}${h.selected_attempt ? ` (showing ${h.selected_attempt})` : ' (showing all)'}`);
  lines.push(`Stages: ${h.stages.map((s) => `${s.label} ${mark[s.state]}`).join(' | ')}`);
  h.retries.forEach((r) => lines.push(`Retry: ${r.worker ?? 'worker not recorded'} ${r.status ?? ''} attempt ${r.attempts}${r.next_eligible_at ? `, next ${r.next_eligible_at}` : ''}`));
  lines.push('Timeline:');
  h.timeline.forEach((e) => lines.push(`  ${e.at ?? '-'}  ${e.collection}  ${[e.type, e.status, e.role].filter(Boolean).join(' / ')}${e.detail ? `  ${e.detail}` : ''}`));
  if (h.markers.length) {
    lines.push('Notes:');
    h.markers.forEach((m) => lines.push(`  - ${m}`));
  }
  return lines.join('\n');
}
