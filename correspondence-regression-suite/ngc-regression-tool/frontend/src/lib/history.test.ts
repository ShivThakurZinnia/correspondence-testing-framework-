import { describe, expect, it } from 'vitest';
import { revisionChanges } from './history';
import type { CaseDetail } from './types';

const base: CaseDetail = {
  scenario_key: 'k', revision: 1, name: 'A', description: null, kafka_kind: 'SOR', kafka_topic_pattern: 'zinnia.correspondence.sor', event_type: 'e', carrier: 'GILI',
  plan_codes: 'P1,P2', enabled: true, expected_json: '{"b":1,"a":{"y":2,"x":1}}', tags: 'client:GILI', payload_artifact_id: 1, golden_artifact_id: null,
  timeout_seconds: 600, change_note: null, created_by: 'u', created_at: '2026-10-01T00:00:00Z', payload_correlation_id: null,
};

describe('revisionChanges', () => {
  it('first revision is "Created"', () => {
    expect(revisionChanges(undefined, base)).toEqual(['Created']);
  });
  it('says nothing visible changed when only formatting differs', () => {
    expect(revisionChanges(base, { ...base, revision: 2, expected_json: '{ "a": {"x":1,"y":2}, "b": 1 }' })).toEqual(['No visible change']);
  });
  it('lists each real change in words', () => {
    const next: CaseDetail = { ...base, revision: 2, name: 'B', plan_codes: 'P1,P2,P3', enabled: false, timeout_seconds: 900, payload_artifact_id: 2, golden_artifact_id: 5, expected_json: '{"a":1}', tags: 'client:GILI,mapper:policy-page' };
    expect(revisionChanges(base, next)).toEqual([
      'Name: “A” → “B”', 'Plan codes: P1, P2 → P1, P2, P3', 'Tags: client:GILI → client:GILI, mapper:policy-page', 'Disabled',
      'Wait time: 600s → 900s', 'Event payload replaced', 'Golden XML replaced', 'Expected outcome changed',
    ]);
  });
});
