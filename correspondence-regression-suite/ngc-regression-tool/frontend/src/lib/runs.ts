import type { CaseStatus } from './types';

export const FAILED_STATUSES: CaseStatus[] = ['FAILED', 'TIMEOUT', 'ERROR'];

export const countOf = (summary: Record<string, number> | undefined, ...statuses: string[]): number =>
  statuses.reduce((n, s) => n + (summary?.[s] ?? 0), 0);

/** Events that have reached a final state, for the progress bar. */
export function finishedEvents(summary: Record<string, number> | undefined): number {
  return countOf(summary, 'PASSED', 'FAILED', 'TIMEOUT', 'ERROR', 'SKIPPED');
}

export function totalEvents(summary: Record<string, number> | undefined): number {
  return Object.values(summary ?? {}).reduce((a, b) => a + b, 0);
}

/** "about 3 min" for a run that sends this many events at this rate (publishing only; NGC needs time on top). */
export function minimumDuration(seconds: number): string {
  if (seconds < 90) return `${seconds}s`;
  const m = Math.round(seconds / 60);
  return m < 90 ? `${m} min` : `${Math.round((m / 60) * 10) / 10} h`;
}

export const FILTERS: { key: string; label: string; statuses: string }[] = [
  { key: 'all', label: 'All', statuses: '' },
  { key: 'failed', label: 'Failed', statuses: 'FAILED,TIMEOUT,ERROR' },
  { key: 'running', label: 'In progress', statuses: 'RUNNING,PENDING' },
  { key: 'passed', label: 'Passed', statuses: 'PASSED' },
  { key: 'skipped', label: 'Skipped', statuses: 'SKIPPED' },
];

/** What a run covered: the tags it selected, or everything. A module-pinned run (CLI) names its module. */
export function runScope(r: { tag_expression?: string | null; module?: string | null }): string {
  const tags = r.tag_expression ? r.tag_expression.split(',').join(' · ') : '';
  if (r.module) return tags ? `${r.module} · ${tags}` : r.module;
  return tags || 'All tests';
}

/** One line describing a group: "2 of 3 plan codes failed", "3 plan codes passed", "1 event". */
export function groupHeadline(g: { total: number; failed: number; passed: number; status: string; cases: { plan_code: string | null }[] }): string {
  const noun = g.cases.some((c) => c.plan_code) ? (g.total === 1 ? 'plan code' : 'plan codes') : g.total === 1 ? 'event' : 'events';
  if (g.failed > 0) return g.total === 1 ? 'failed' : `${g.failed} of ${g.total} ${noun} failed`;
  if (g.status === 'RUNNING') return `${g.total} ${noun} in progress`;
  if (g.status === 'PASSED') return g.total === 1 ? 'passed' : `${g.total} ${noun} passed`;
  return `${g.total} ${noun}`;
}

export type AgeBucket = 'day' | 'week' | 'older';

export const BUCKET_LABEL: Record<AgeBucket, string> = {
  day: 'Last 24 hours',
  week: 'Previous 5 days',
  older: 'Older',
};

const DAY_MS = 24 * 60 * 60 * 1000;

/** Which section a run belongs to by its start (or queue) time: the last day, the 5 days before that, or older. */
export function bucketOf(iso: string | null | undefined, now: number = Date.now()): AgeBucket {
  const age = iso ? now - new Date(iso).getTime() : 0;
  if (age < DAY_MS) return 'day';
  return age < 6 * DAY_MS ? 'week' : 'older';
}
