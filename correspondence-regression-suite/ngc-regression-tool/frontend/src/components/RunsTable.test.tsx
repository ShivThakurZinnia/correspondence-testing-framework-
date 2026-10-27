import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { RunRow } from '../lib/types';
import { RunsTable } from './RunsTable';

const run = (over: Partial<RunRow>): RunRow => ({
  run_key: 'abcdef12-0000-4000-8000-000000000000', status: 'PASSED', trigger_source: 'SCHEDULE', triggered_by: 'scheduler', env: 'qa', module: null,
  module_version: null, tag_expression: 'mapper:anniversary', queued_at: '2026-10-27T10:00:00Z', started_at: '2026-10-27T10:00:00Z', finished_at: '2026-10-27T10:03:00Z', total_cases: 3, passed_cases: 3,
  failed_cases: 0, ngc_build_version: null, rule_set_version: null, schedule_name: 'daily-qa', duration_seconds: 180, ...over,
});

describe('RunsTable', () => {
  it('links each run and shows result, schedule name and duration', () => {
    render(<MemoryRouter><RunsTable runs={[run({}), run({ run_key: 'ffff0000-0000-4000-8000-000000000000', status: 'FAILED', passed_cases: 2, failed_cases: 1, schedule_name: null, triggered_by: 'admin', trigger_source: 'CLI' })]} /></MemoryRouter>);

    expect(screen.getByRole('link', { name: '#abcdef12' })).toHaveAttribute('href', '/runs/abcdef12-0000-4000-8000-000000000000');
    expect(screen.getByText('3/3 passed')).toBeInTheDocument();
    expect(screen.getByText('daily-qa')).toBeInTheDocument();
    expect(screen.getByText('2/3 passed')).toBeInTheDocument();
    expect(screen.getByText(/1 failed/)).toBeInTheDocument();
    expect(screen.getAllByText('3m 0s')).toHaveLength(2);
    expect(screen.getByLabelText('FAILED')).toBeInTheDocument();
    expect(screen.getAllByText('mapper:anniversary')).toHaveLength(2);
  });

  it('says so when there are no runs', () => {
    render(<MemoryRouter><RunsTable runs={[]} /></MemoryRouter>);
    expect(screen.getByText('No runs yet.')).toBeInTheDocument();
  });
});
