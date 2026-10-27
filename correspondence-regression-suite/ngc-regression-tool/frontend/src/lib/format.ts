import type { CaseStatus, RunStatus, Stage } from './types';

export type Tone = 'ok' | 'bad' | 'warn' | 'run' | 'idle';

export function statusTone(status: RunStatus | CaseStatus | string | null | undefined): Tone {
  switch (status) {
    case 'PASSED':
      return 'ok';
    case 'FAILED':
    case 'ERROR':
    case 'TIMEOUT':
      return 'bad';
    case 'RUNNING':
    case 'QUEUED':
    case 'PENDING':
      return 'run';
    case 'CANCELLED':
    case 'SKIPPED':
      return 'warn';
    default:
      return 'idle';
  }
}

export function formatDuration(seconds: number | null | undefined): string {
  if (seconds == null || seconds < 0) return '–';
  if (seconds < 60) return `${seconds}s`;
  const m = Math.floor(seconds / 60);
  if (m < 60) return `${m}m ${seconds % 60}s`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

export function timeAgo(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return '–';
  const diff = Math.round((now - new Date(iso).getTime()) / 1000);
  if (diff < 0) return `in ${formatDuration(-diff)}`;
  if (diff < 5) return 'just now';
  if (diff < 86400 * 2) return `${formatDuration(diff)} ago`;
  return `${Math.floor(diff / 86400)}d ago`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '–';
  return new Date(iso).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export const shortKey = (key: string): string => key.slice(0, 8);

export type StageState = 'done' | 'active' | 'failed' | 'skipped' | 'todo';

/** Colours each stage: reached = done; the first unreached one is active while running, failed if the case failed. */
export function stageStates(progress: Stage[], caseStatus: CaseStatus | string): (Stage & { state: StageState })[] {
  const failed = ['FAILED', 'ERROR', 'TIMEOUT'].includes(caseStatus);
  const running = ['RUNNING', 'PENDING'].includes(caseStatus);
  let marked = false;
  return progress.map((s) => {
    if (s.reached) return { ...s, state: 'done' as StageState };
    if (!marked && failed) {
      marked = true;
      return { ...s, state: 'failed' as StageState };
    }
    if (!marked && running) {
      marked = true;
      return { ...s, state: 'active' as StageState };
    }
    return { ...s, state: (caseStatus === 'PASSED' ? 'skipped' : 'todo') as StageState };
  });
}

export function percent(part: number, whole: number): number {
  return whole === 0 ? 0 : Math.round((part / whole) * 100);
}
