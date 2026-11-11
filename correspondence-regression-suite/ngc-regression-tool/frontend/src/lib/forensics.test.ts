import { describe, expect, it } from 'vitest';
import { factSheet, ngcTone, params } from './forensics';
import type { ForensicsHistory } from './types';

const history: ForensicsHistory = {
  correlation_id: 'c-1',
  selected_attempt: 'u2',
  attempt_count: 2,
  attempts: [
    { unique_id: 'u1', correlation_id: 'c-1', carrier: 'GILI', event_type: 'policy.x', policy_number: 'P1', plan_code: 'G1', source: 'zahara', status: 'FAILED', error_category: 'TECHNICAL_FAILURE', error: 'old', retry_in_progress: false, created_at: '2026-10-28T10:00:00Z', updated_at: null, targets: [] },
    { unique_id: 'u2', correlation_id: 'c-1', carrier: 'GILI', event_type: 'policy.x', policy_number: 'P1', plan_code: 'G1', source: 'zahara', status: 'FAILED', error_category: 'TECHNICAL_FAILURE', error: 'Zahara 404', retry_in_progress: false, created_at: '2026-10-28T11:00:00Z', updated_at: null, targets: [{ role: 'OWNER', status: 'TECHNICAL_FAILURE', delivery_type: 'PRINT', error: null }] },
  ],
  stages: [
    { code: 'RECEIVED', label: 'Received', state: 'done', at: null },
    { code: 'SENT_TO_CDS', label: 'Sent to CDS', state: 'failed', at: null },
    { code: 'PDF_STARTED', label: 'PDF started', state: 'pending', at: null },
  ],
  timeline: [{ at: '2026-10-28T11:00:00Z', collection: 'technical_failures', type: 'technical failure', status: 'TECHNICAL_FAILURE', role: null, detail: 'worker: CarrierDetailsWorker', attempt: 'u2' }],
  retries: [{ worker: 'CarrierDetailsWorker', status: 'RETRY_ATTEMPT_3', attempts: 3, last_attempt_at: null, next_eligible_at: '2026-10-28T13:00:00Z', attempt: 'u2' }],
  markers: ['Replayed: 2 processing attempts share this correlationId.'],
};

describe('forensics helpers', () => {
  it('maps NGC statuses to a tone', () => {
    expect(['SENT_TO_CDS', 'FAILED', 'TECHNICAL_FAILURE', 'PROCESSING', 'FAILED_REPLAY_ATTEMPT_2', 'MYSTERY', null].map(ngcTone))
      .toEqual(['ok', 'bad', 'bad', 'run', 'warn', 'idle', 'idle']);
  });

  it('leaves empty values out of query strings', () => {
    expect(params({ carrier: 'GILI', eventType: undefined, outcome: '' }).toString()).toBe('carrier=GILI');
  });

  it('writes a plain-text fact sheet for an incident ticket', () => {
    const text = factSheet(history, new Date('2026-10-28T12:00:00Z'));

    expect(text).toContain('Event history for correlationId c-1');
    expect(text).toContain('Carrier GILI | Event policy.x | Policy P1 | Plan G1');
    expect(text).toContain('Attempt u2 created 2026-10-28T11:00:00Z');
    expect(text).toContain('Status FAILED (TECHNICAL_FAILURE): Zahara 404');
    expect(text).toContain('Target OWNER: TECHNICAL_FAILURE via PRINT');
    expect(text).toContain('Stages: Received ✓ | Sent to CDS ✗ | PDF started …');
    expect(text).toContain('Retry: CarrierDetailsWorker RETRY_ATTEMPT_3 attempt 3, next 2026-10-28T13:00:00Z');
    expect(text).toContain('technical_failures  technical failure / TECHNICAL_FAILURE  worker: CarrierDetailsWorker');
    expect(text).toContain('- Replayed: 2 processing attempts share this correlationId.');
    expect(text).not.toContain('old');
  });
});
