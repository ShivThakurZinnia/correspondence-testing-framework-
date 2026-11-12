import { useInfiniteQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { RunsTable } from '../components/RunsTable';
import { UpcomingDialog } from '../components/UpcomingDialog';
import { useQuery } from '@tanstack/react-query';
import { Card, Empty, ErrorNote, PageHeader } from '../components/ui';
import { api } from '../lib/api';
import { BUCKET_LABEL, bucketOf, type AgeBucket } from '../lib/runs';
import type { RunRow } from '../lib/types';

const PAGE = 50;
const ORDER: AgeBucket[] = ['day', 'week', 'older'];

export function Runs() {
  const [filters, setFilters] = useState({ env: '', status: '', trigger: '' });
  const [showOlder, setShowOlder] = useState(false);
  const [showUpcoming, setShowUpcoming] = useState(false);
  const envs = useQuery({ queryKey: ['envs'], queryFn: () => api<{ code: string }[]>('/api/reference/environments') });
  const runs = useInfiniteQuery({
    queryKey: ['runs', filters],
    initialPageParam: 0,
    queryFn: ({ pageParam }) => {
      const query = new URLSearchParams({ page: String(pageParam), size: String(PAGE) });
      Object.entries(filters).forEach(([k, v]) => v && query.set(k, v));
      return api<RunRow[]>(`/api/runs?${query}`);
    },
    getNextPageParam: (last, all) => (last.length < PAGE ? undefined : all.length),
    refetchInterval: (q) => (q.state.data?.pages.some((p) => p.some((r) => r.status === 'RUNNING' || r.status === 'QUEUED')) ? 2000 : 15000),
  });
  const all = useMemo(() => runs.data?.pages.flat() ?? [], [runs.data]);
  const buckets = useMemo(() => {
    const out: Record<AgeBucket, RunRow[]> = { day: [], week: [], older: [] };
    all.forEach((r) => out[bucketOf(r.started_at ?? r.queued_at)].push(r));
    return out;
  }, [all]);

  // Keep loading until the last day and the 5 days before it are complete, i.e. we reached a run older than that.
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = runs;
  useEffect(() => {
    if (hasNextPage && !isFetchingNextPage && buckets.older.length === 0) void fetchNextPage();
  }, [hasNextPage, isFetchingNextPage, buckets.older.length, fetchNextPage]);

  const set = (k: keyof typeof filters) => (e: { target: { value: string } }) => {
    setFilters({ ...filters, [k]: e.target.value });
    setShowOlder(false);
  };

  return (
    <>
      <PageHeader
        title="Runs"
        subtitle="Every run from the UI, CLI, schedules and CI: the last day, the 5 days before, then older on request."
        actions={<button className="btn" onClick={() => setShowUpcoming(true)}>Upcoming scheduled runs</button>}
      />
      {showUpcoming && <UpcomingDialog onClose={() => setShowUpcoming(false)} />}
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
      {all.length === 0 && !runs.isFetching && <Card><Empty>No runs yet.</Empty></Card>}
      {ORDER.filter((b) => b !== 'older').map((b) =>
        buckets[b].length > 0 ? (
          <Card key={b} title={`${BUCKET_LABEL[b]} · ${buckets[b].length}`}>
            <RunsTable runs={buckets[b]} />
          </Card>
        ) : null,
      )}
      {(buckets.older.length > 0 || hasNextPage) && (
        <Card title={`${BUCKET_LABEL.older}${buckets.older.length ? ` · ${buckets.older.length}${hasNextPage ? '+' : ''}` : ''}`}>
          {!showOlder ? (
            <button className="btn" onClick={() => setShowOlder(true)}>View older runs</button>
          ) : (
            <>
              {buckets.older.length > 0 && <RunsTable runs={buckets.older} />}
              <div className="pager">
                <button className="btn" onClick={() => setShowOlder(false)}>Hide older runs</button>
                {hasNextPage ? (
                  <button className="btn" disabled={isFetchingNextPage} onClick={() => void fetchNextPage()}>{isFetchingNextPage ? 'Loading…' : 'View more'}</button>
                ) : (
                  <span className="muted">No more runs</span>
                )}
              </div>
            </>
          )}
        </Card>
      )}
    </>
  );
}
