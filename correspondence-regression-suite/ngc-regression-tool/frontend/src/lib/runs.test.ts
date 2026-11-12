import { describe, expect, it } from 'vitest';
import { bucketOf, countOf, finishedEvents, groupHeadline, minimumDuration, runScope, totalEvents } from './runs';

const summary = { ERROR: 1, FAILED: 2, TIMEOUT: 1, RUNNING: 3, PENDING: 10, SKIPPED: 0, PASSED: 20 };

describe('run counters', () => {
  it('adds up statuses', () => {
    expect(countOf(summary, 'FAILED', 'TIMEOUT', 'ERROR')).toBe(4);
    expect(countOf(undefined, 'FAILED')).toBe(0);
    expect(totalEvents(summary)).toBe(37);
  });
  it('counts only events in a final state as finished', () => {
    expect(finishedEvents(summary)).toBe(24);
  });
});

describe('minimumDuration', () => {
  it('reads naturally', () => {
    expect([30, 89, 120, 600, 5400, 7200].map(minimumDuration)).toEqual(['30s', '89s', '2 min', '10 min', '1.5 h', '2 h']);
  });
});

describe('runScope', () => {
  it('names what a run covered', () => {
    expect(runScope({})).toBe('All tests');
    expect(runScope({ tag_expression: 'mapper:anniversary,client:GILI' })).toBe('mapper:anniversary · client:GILI');
    expect(runScope({ module: 'policy-page' })).toBe('policy-page');
    expect(runScope({ module: 'policy-page', tag_expression: 'client:GILI' })).toBe('policy-page · client:GILI');
  });
});

describe('groupHeadline', () => {
  const cases = [{ plan_code: 'A' }, { plan_code: 'B' }, { plan_code: 'C' }];
  it('says how many plan codes failed', () => {
    expect(groupHeadline({ total: 3, failed: 2, passed: 1, status: 'FAILED', cases })).toBe('2 of 3 plan codes failed');
  });
  it('says everything passed', () => {
    expect(groupHeadline({ total: 3, failed: 0, passed: 3, status: 'PASSED', cases })).toBe('3 plan codes passed');
  });
  it('talks about events when there are no plan codes', () => {
    expect(groupHeadline({ total: 2, failed: 0, passed: 0, status: 'RUNNING', cases: [{ plan_code: null }, { plan_code: null }] })).toBe('2 events in progress');
  });
});

describe('bucketOf', () => {
  const now = new Date('2026-11-12T12:00:00Z').getTime();
  it('splits runs into the last day, the 5 days before, and older', () => {
    expect(bucketOf('2026-11-12T01:00:00Z', now)).toBe('day');
    expect(bucketOf('2026-11-10T12:00:00Z', now)).toBe('week');
    expect(bucketOf('2026-11-06T12:00:01Z', now)).toBe('week');
    expect(bucketOf('2026-11-05T12:00:00Z', now)).toBe('older');
    expect(bucketOf(null, now)).toBe('day');
  });
});
