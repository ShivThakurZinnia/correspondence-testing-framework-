import { describe, expect, it } from 'vitest';
import { countOf, finishedEvents, minimumDuration, runScope, totalEvents } from './runs';

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
