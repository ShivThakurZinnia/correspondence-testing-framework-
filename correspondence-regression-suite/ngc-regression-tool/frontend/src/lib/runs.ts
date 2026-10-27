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
