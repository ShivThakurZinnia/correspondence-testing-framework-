import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { RunsTable } from '../components/RunsTable';
import { Card, ErrorNote, PageHeader } from '../components/ui';
import { api } from '../lib/api';
import type { RunRow } from '../lib/types';

const PAGE = 25;

export function Runs() {
  const [filters, setFilters] = useState({ env: '', status: '', trigger: '' });
  const [page, setPage] = useState(0);
  const envs = useQuery({ queryKey: ['envs'], queryFn: () => api<{ code: string }[]>('/api/reference/environments') });
  const query = new URLSearchParams({ page: String(page), size: String(PAGE) });
  Object.entries(filters).forEach(([k, v]) => v && query.set(k, v));
  const runs = useQuery({
    queryKey: ['runs', filters, page],
    queryFn: () => api<RunRow[]>(`/api/runs?${query}`),
    refetchInterval: (q) => (q.state.data?.some((r) => r.status === 'RUNNING' || r.status === 'QUEUED') ? 2000 : 15000),
  });
  const set = (k: keyof typeof filters) => (e: { target: { value: string } }) => {
    setFilters({ ...filters, [k]: e.target.value });
    setPage(0);
  };

  return (
    <>
      <PageHeader title="Runs" subtitle="Every run from the UI, CLI, schedules and CI, newest first." />
      <div className="filters">
        <select aria-label="Environment" value={filters.env} onChange={set('env')}>
          <option value="">All environments</option>
          {envs.data?.map((e) => <option key={e.code}>{e.code}</option>)}
        </select>
        <select aria-label="Status" value={filters.status} onChange={set('status')}>
          <option value="">Any status</option>
          {['PASSED', 'FAILED', 'ERROR', 'RUNNING', 'QUEUED'].map((s) => <option key={s}>{s}</option>)}
        </select>
        <select aria-label="Trigger" value={filters.trigger} onChange={set('trigger')}>
          <option value="">Any trigger</option>
          {['UI', 'CLI', 'SCHEDULE', 'MCP', 'CI'].map((s) => <option key={s}>{s}</option>)}
        </select>
      </div>
      <ErrorNote error={runs.error} />
      <Card>
        <RunsTable runs={runs.data ?? []} />
        <div className="pager">
          <button className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>← Newer</button>
          <span className="muted">Page {page + 1}</span>
          <button className="btn" disabled={(runs.data?.length ?? 0) < PAGE} onClick={() => setPage(page + 1)}>Older →</button>
        </div>
      </Card>
    </>
  );
}
