import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { CaseRow } from '../components/CaseRow';
import { Card, Empty, ErrorNote, PageHeader, StatusBadge, StatusIcon } from '../components/ui';
import { api } from '../lib/api';
import { formatDateTime, formatDuration, percent, shortKey } from '../lib/format';
import { countOf, FILTERS, finishedEvents, groupHeadline, runScope, totalEvents } from '../lib/runs';
import type { RunCompare, RunDetail as Run } from '../lib/types';

const PAGE = 50;

export function RunDetail() {
  const { runKey = '' } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [filter, setFilter] = useState('all');
  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  useEffect(() => {
    const t = setTimeout(() => {
      setQ(search);
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  const statuses = FILTERS.find((f) => f.key === filter)?.statuses ?? '';
  const { data: run, error } = useQuery({
    queryKey: ['run', runKey, statuses, q, page],
    queryFn: () => api<Run>(`/api/runs/${runKey}?group=scenario&size=${PAGE}&page=${page}${statuses ? `&status=${statuses}` : ''}${q ? `&q=${encodeURIComponent(q)}` : ''}`),
    refetchInterval: (query) => (query.state.data && ['RUNNING', 'QUEUED'].includes(query.state.data.status) ? 2000 : false),
    placeholderData: keepPreviousData,
  });
  const finished = !!run && !['RUNNING', 'QUEUED'].includes(run.status);
  const compare = useQuery({ queryKey: ['compare', runKey], queryFn: () => api<RunCompare>(`/api/runs/${runKey}/compare`), enabled: finished });

  const cancel = useMutation({ mutationFn: () => api(`/api/runs/${runKey}/cancel`, { method: 'POST' }), onSuccess: () => qc.invalidateQueries({ queryKey: ['run', runKey] }) });
  const rerun = useMutation({
    mutationFn: () => api<{ runKey: string }>(`/api/runs/${runKey}/rerun-failed`, { method: 'POST' }),
    onSuccess: (r) => navigate(`/runs/${r.runKey}`),
  });

  if (error) return <ErrorNote error={error} />;
  if (!run) return <Empty>Loading…</Empty>;

  const total = run.total_cases || totalEvents(run.summary);
  const done = finishedEvents(run.summary);
  const failed = countOf(run.summary, 'FAILED', 'TIMEOUT', 'ERROR');
  const seconds = run.started_at ? Math.round(((run.finished_at ? new Date(run.finished_at).getTime() : Date.now()) - new Date(run.started_at).getTime()) / 1000) : null;
  const from = run.matching === 0 ? 0 : run.page * run.size + 1;
  const to = Math.min(run.matching, (run.page + 1) * run.size);
  const open = (status: string) => status === 'FAILED' || status === 'RUNNING';
  const diff = compare.data;

  return (
    <>
      <PageHeader
        title={
          <span className="title-row">
            <StatusIcon status={run.status} size={26} /> Run <span className="mono">#{shortKey(run.run_key)}</span>
            <StatusBadge status={run.status} />
          </span>
        }
        subtitle={
          <>
            {runScope(run)} on <strong>{run.env}</strong> · {run.trigger_source.toLowerCase()}
            {run.triggered_by ? ` by ${run.triggered_by}` : ''} · {formatDateTime(run.started_at ?? run.queued_at)} · {formatDuration(seconds)}
            {run.rerun_of && <> · re-run of <Link to={`/runs/${run.rerun_of}`} className="mono">#{shortKey(run.rerun_of)}</Link></>}
            {run.max_in_flight != null || run.events_per_second != null ? ` · paced ${run.max_in_flight ?? 'default'} in flight, ${run.events_per_second ?? 'default'}/s` : ''}
          </>
        }
        actions={
          <>
            {!finished && (
              <button className="btn danger" disabled={cancel.isPending || run.cancel_requested} onClick={() => window.confirm('Cancel this run? Events not yet sent are skipped.') && cancel.mutate()}>
                {run.cancel_requested ? 'Cancelling…' : 'Cancel run'}
              </button>
            )}
            {finished && failed > 0 && (
              <button className="btn" disabled={rerun.isPending} onClick={() => rerun.mutate()}>↻ Re-run {failed} failed</button>
            )}
            <Link className="btn" to="/runs">← All runs</Link>
          </>
        }
      />
      <ErrorNote error={cancel.error ?? rerun.error} />

      {!finished && (
        <div className="progress-line" title={`${done} of ${total} events finished`}>
          <span style={{ width: `${percent(done, total)}%` }} />
        </div>
      )}
      <div className="facts">
        <div><span className="muted">Events</span><strong>{total}</strong></div>
        <div><span className="muted">Done</span><strong>{done}{!finished && ` (${percent(done, total)}%)`}</strong></div>
        <div><span className="muted">Passed</span><strong className="ok-text">{countOf(run.summary, 'PASSED')}</strong></div>
        <div><span className="muted">Failed</span><strong className={failed ? 'bad-text' : ''}>{failed}</strong></div>
        {countOf(run.summary, 'SKIPPED') > 0 && <div><span className="muted">Skipped</span><strong>{countOf(run.summary, 'SKIPPED')}</strong></div>}
        <div><span className="muted">NGC build</span><strong>{run.ngc_build_version ?? '–'}</strong></div>
      </div>

      {finished && diff?.previous && (
        <div className={diff.new_failures ? 'note error' : 'note ok'} role="status">
          <strong>
            {diff.new_failures ? `${diff.new_failures} new failure${diff.new_failures === 1 ? '' : 's'}` : 'No new failures'}
          </strong>{' '}
          since <Link to={`/runs/${diff.previous.run_key}`} className="mono">#{shortKey(diff.previous.run_key)}</Link> ({formatDateTime(diff.previous.finished_at)}) ·{' '}
          {diff.fixed ?? 0} fixed · {diff.still_failing ?? 0} still failing · {diff.new_cases ?? 0} new
          {!!diff.new_failure_events?.length && (
            <ul className="plain regression-list">
              {diff.new_failure_events.slice(0, 15).map((e) => (
                <li key={e.correlation_id}>
                  <Link to={`/events/${e.correlation_id}`}>{e.scenario_key}{e.plan_code ? ` · ${e.plan_code}` : ''}</Link>
                  <span className="muted"> was {e.previous_status.toLowerCase()}, now {e.status.toLowerCase()}</span>
                </li>
              ))}
              {diff.new_failure_events.length > 15 && <li className="muted">…and {diff.new_failures! - 15} more (filter “Failed” below)</li>}
            </ul>
          )}
        </div>
      )}
      {run.details_purged && <div className="note">Detailed observations and assertions for this run were removed after the retention period; results and failure reasons remain.</div>}

      <Card
        title="Events"
        actions={<input type="search" aria-label="Search events" placeholder="Search scenario, plan code or correlation id" value={search} onChange={(e) => setSearch(e.target.value)} className="search" />}
      >
        <div className="seg filter-seg" role="tablist" aria-label="Filter events">
          {FILTERS.map((f) => {
            const count = f.statuses ? countOf(run.summary, ...f.statuses.split(',')) : total;
            return (
              <button key={f.key} role="tab" aria-selected={filter === f.key} className={filter === f.key ? 'on' : ''} onClick={() => { setFilter(f.key); setPage(0); }}>
                {f.label} <span className="count">{count}</span>
              </button>
            );
          })}
        </div>
        {run.groups.length === 0 ? (
          <Empty>No events match.</Empty>
        ) : (
          <div className="case-list">
            {run.groups.map((g) =>
              g.total === 1 && g.cases.length === 1 ? (
                <CaseRow key={g.test_case_id} c={g.cases[0]} />
              ) : (
                <details key={g.test_case_id} className={`group group-${g.status.toLowerCase()}`} open={open(g.status)}>
                  <summary>
                    <StatusIcon status={g.status} size={22} />
                    <div className="case-title">
                      <strong>{g.scenario_name}</strong>
                      <span className="chip">{g.carrier}</span>
                      <span className="muted">{g.event_type}</span>
                    </div>
                    <div className="group-meta">
                      <span className={g.failed ? 'bad-text' : 'muted'}>{groupHeadline(g)}</span>
                      {g.cases.length < g.total && <span className="muted small">showing {g.cases.length} of {g.total}</span>}
                    </div>
                  </summary>
                  {g.cases.map((c) => <CaseRow key={c.correlation_id} c={c} inGroup />)}
                </details>
              ),
            )}
          </div>
        )}
        <div className="pager">
          <button className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>← Previous</button>
          <span className="muted">Test cases {from}–{to} of {run.matching} · {run.matching_events} events</span>
          <button className="btn" disabled={to >= run.matching} onClick={() => setPage(page + 1)}>Next →</button>
        </div>
      </Card>
    </>
  );
}
