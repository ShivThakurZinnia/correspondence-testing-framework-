import { describe, expect, it } from 'vitest';
import { formatDuration, percent, stageStates, statusTone, timeAgo } from './format';
import type { Stage } from './types';

const stages = (reached: boolean[]): Stage[] => reached.map((r, i) => ({ code: `S${i}`, label: `Stage ${i}`, reached: r }));

describe('statusTone', () => {
  it('maps every run and case status to a tone', () => {
    expect(['PASSED', 'FAILED', 'ERROR', 'TIMEOUT', 'RUNNING', 'QUEUED', 'PENDING', 'CANCELLED', 'SKIPPED', undefined].map(statusTone)).toEqual([
      'ok', 'bad', 'bad', 'bad', 'run', 'run', 'run', 'warn', 'warn', 'idle',
    ]);
  });
});

describe('formatDuration', () => {
  it('formats seconds, minutes and hours', () => {
    expect([null, -1, 0, 59, 60, 125, 3600, 7500].map((s) => formatDuration(s as number | null))).toEqual(['–', '–', '0s', '59s', '1m 0s', '2m 5s', '1h 0m', '2h 5m']);
  });
});

describe('timeAgo', () => {
  const now = Date.parse('2026-10-27T12:00:00Z');
  it('reads naturally for past and future times', () => {
    expect(timeAgo(null, now)).toBe('–');
    expect(timeAgo('2026-10-27T11:59:59Z', now)).toBe('just now');
    expect(timeAgo('2026-10-27T11:57:00Z', now)).toBe('3m 0s ago');
    expect(timeAgo('2026-10-27T15:00:00Z', now)).toBe('in 3h 0m');
    expect(timeAgo('2026-10-20T12:00:00Z', now)).toBe('7d ago');
  });
});

describe('stageStates', () => {
  it('marks the first unreached stage as failed when the case failed', () => {
    expect(stageStates(stages([true, true, false, false]), 'FAILED').map((s) => s.state)).toEqual(['done', 'done', 'failed', 'todo']);
  });
  it('marks the first unreached stage as active while running', () => {
    expect(stageStates(stages([true, false, false]), 'RUNNING').map((s) => s.state)).toEqual(['done', 'active', 'todo']);
  });
  it('shows unreached stages of a passed case as skipped, not failed', () => {
    expect(stageStates(stages([true, true, false]), 'PASSED').map((s) => s.state)).toEqual(['done', 'done', 'skipped']);
  });
});

describe('percent', () => {
  it('handles zero totals', () => {
    expect(percent(1, 0)).toBe(0);
    expect(percent(1, 3)).toBe(33);
  });
});
