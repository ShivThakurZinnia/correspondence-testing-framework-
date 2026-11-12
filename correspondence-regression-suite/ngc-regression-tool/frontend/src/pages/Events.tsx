import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Card, Empty, ErrorNote, PageHeader, StatusIcon } from '../components/ui';
import { api } from '../lib/api';
import { formatDuration, shortKey, timeAgo } from '../lib/format';
import { BUCKET_LABEL, bucketOf, runScope } from '../lib/runs';
import type { CaseRow, EventRun, Tag } from '../lib/types';

const RUNS_PER_PAGE = 10;
const FLAT_PER_PAGE = 100;

function EventRows({ events }: { events: CaseRow[] }) {
  return (
    <table className="table">
      <thead><tr><th style={{ width: 28 }} /><th>Scenario</th><th>Correlation id</th><th>NGC status</th><th>When</th></tr></thead>
      <tbody>
        {events.map((e) => (
          <tr key={e.correlation_id} className={e.status === 'FAILED' || e.status === 'ERROR' || e.status === 'TIMEOUT' ? 'row-failed' : ''}>
            <td><StatusIcon status={e.status} /></td>
            <td><Link to={`/events/${e.correlation_id}`} className="strong">{e.scenario_name}</Link>{e.plan_code && <span className="chip plan-chip">{e.plan_code}</span>}</td>
            <td className="mono small">{e.correlation_id}</td>
            <td>{e.final_incoming_status ?? '–'}</td>
            <td>{timeAgo(e.started_at)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function Events() {
  const [params, setParams] = useSearchParams();
  const grouped = params.get('group') !== 'none';
  const [f, setF] = useState({ correlationId: '', scenario: '', status: '', env: '', tag: '', run_key: params.get('run') ?? '' });
  const [debounced, setDebounced] = useState(f);
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const toggle = (key: string) => setExpanded((cur) => { const next = new Set(cur); if (!next.delete(key)) next.add(key); return next; });
  const tags = useQuery({ queryKey: ['tags'], queryFn: () => api<Tag[]>('/api/reference/tags') });
  const query = new URLSearchParams({ page: String(page), size: String(grouped ? RUNS_PER_PAGE : FLAT_PER_PAGE) });
  Object.entries(debounced).forEach(([k, v]) => v && query.set(k, v));
  const events = useQuery({
    queryKey: ['events', grouped, debounced, page],
    queryFn: () => api<(EventRun | CaseRow)[]>(`/api/events${grouped ? '/by-run' : ''}?${query}`),
  });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  const setGrouped = (g: boolean) => {
    const next = new URLSearchParams(params);
    if (g) next.delete('group');
    else next.set('group', 'none');
    setParams(next, { replace: true });
    setPage(0);
  };
  const rows = events.data ?? [];
  const perPage = grouped ? RUNS_PER_PAGE : FLAT_PER_PAGE;

  return (
    <>
      <PageHeader
        title="Events"
        subtitle="Every event ever played, grouped by the run that played it. Search by correlation id, scenario, result or tag."
        actions={
          <>
          {grouped && rows.length > 0 && (
            <>
              <button className="btn" onClick={() => setExpanded(new Set((rows as EventRun[]).map((r) => r.run_key)))}>Expand all</button>
              <button className="btn" onClick={() => setExpanded(new Set())}>Collapse all</button>
            </>
          )}
          <div className="seg" role="tablist" aria-label="Grouping">
            <button role="tab" aria-selected={grouped} className={grouped ? 'on' : ''} onClick={() => setGrouped(true)}>By run</button>
            <button role="tab" aria-selected={!grouped} className={!grouped ? 'on' : ''} onClick={() => setGrouped(false)}>Flat list</button>
          </div>
          </>
        }
      />
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setDebounced(f); setPage(0); }}>
        <input placeholder="Correlation id (any part)" value={f.correlationId} onChange={set('correlationId')} />
        <input placeholder="Scenario key" value={f.scenario} onChange={set('scenario')} />
        <input placeholder="Run key" aria-label="Run key" value={f.run_key} onChange={set('run_key')} />
        <select aria-label="Status" value={f.status} onChange={set('status')}>
          <option value="">Any result</option>
          {['PASSED', 'FAILED', 'TIMEOUT', 'ERROR', 'RUNNING'].map((s) => <option key={s}>{s}</option>)}
        </select>
        <select aria-label="Tag" value={f.tag} onChange={set('tag')}>
          <option value="">Any tag</option>
          {tags.data?.map((t) => <option key={`${t.category}:${t.code}`} value={`${t.category.toLowerCase()}:${t.code}`}>{t.category.toLowerCase()}:{t.code}</option>)}
        </select>
        <button className="btn primary">Search</button>
      </form>
      <ErrorNote error={events.error} />
      {rows.length === 0 ? (
        <Card><Empty>No events match.</Empty></Card>
      ) : grouped ? (
        (rows as EventRun[]).map((r, i, all) => {
          const isOpen = expanded.has(r.run_key);
          const bucket = bucketOf(r.started_at ?? r.queued_at);
          const firstOfBucket = i === 0 || bucketOf(all[i - 1].started_at ?? all[i - 1].queued_at) !== bucket;
          return (
          <div key={r.run_key}>
          {firstOfBucket && <h3 className="bucket-title">{BUCKET_LABEL[bucket]}</h3>}
          <Card className={`run-block${isOpen ? '' : ' collapsed'}`}>
            <div className="run-block-head toggle" role="button" tabIndex={0} aria-expanded={isOpen} onClick={() => toggle(r.run_key)}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(r.run_key); } }}>
              <span aria-hidden className="chev">{isOpen ? '▾' : '▸'}</span>
              <StatusIcon status={r.status} size={22} />
              <Link to={`/runs/${r.run_key}`} className="mono strong" onClick={(e) => e.stopPropagation()}>Run #{shortKey(r.run_key)}</Link>
              <span>{runScope(r)}</span>
              <span className="chip">{r.env}</span>
              <span className="muted">{r.trigger_source.toLowerCase()} {r.schedule_name ?? r.triggered_by ?? ''}</span>
              <span className="grow" />
              <span className={r.failed_cases > 0 ? 'bad-text' : 'muted'}>{r.passed_cases}/{r.total_cases} passed{r.failed_cases > 0 ? ` · ${r.failed_cases} failed` : ''}</span>
              <span className="muted small">{r.matching} {r.matching === 1 ? 'event' : 'events'}</span>
              <span className="muted" title={r.queued_at}>{timeAgo(r.started_at ?? r.queued_at)}{r.finished_at && r.started_at ? ` · ${formatDuration(Math.round((new Date(r.finished_at).getTime() - new Date(r.started_at).getTime()) / 1000))}` : ''}</span>
            </div>
            {isOpen && (
              <>
                <EventRows events={r.events} />
                {r.matching > r.events.length && (
                  <div className="muted small" style={{ padding: '8px 4px 0' }}>
                    Showing {r.events.length} of {r.matching} matching events. <Link to={`/runs/${r.run_key}`}>Open the run</Link> for the rest.
                  </div>
                )}
              </>
            )}
          </Card>
          </div>
          );
        })
      ) : (
        <Card>
          <EventRows events={rows as CaseRow[]} />
        </Card>
      )}
      <div className="pager">
        <button className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>← Newer</button>
        <span className="muted">Page {page + 1}{grouped ? ' (runs)' : ''}</span>
        <button className="btn" disabled={rows.length < perPage} onClick={() => setPage(page + 1)}>Older →</button>
      </div>
    </>
  );
}
