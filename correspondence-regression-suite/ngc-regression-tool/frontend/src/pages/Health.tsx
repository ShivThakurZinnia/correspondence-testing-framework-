import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Sparkline } from '../components/Sparkline';
import { Card, Empty, ErrorNote, PageHeader } from '../components/ui';
import { api } from '../lib/api';
import { formatDateTime, formatDuration, timeAgo } from '../lib/format';
import type { HealthResponse, HistoryPoint, Level } from '../lib/types';

const WINDOWS = [
  { label: 'Last 24 hours', hours: 24 },
  { label: 'Last 3 days', hours: 72 },
  { label: 'Last 7 days', hours: 168 },
  { label: 'Last 30 days', hours: 720 },
];

const LEVEL_TEXT: Record<Level, string> = { ok: 'Healthy', warn: 'Needs attention', bad: 'Problem', info: 'Note' };

export const pct = (v: number | null | undefined): string => (v == null ? '–' : `${Math.round(v * 100)}%`);

export function minutes(m: number | null | undefined): string {
  if (m == null) return '–';
  if (m < 60) return `${m}m`;
  if (m < 1440) return m % 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${Math.floor(m / 60)}h`;
  const h = Math.floor((m % 1440) / 60);
  return h ? `${Math.floor(m / 1440)}d ${h}h` : `${Math.floor(m / 1440)}d`;
}

function Dot({ level }: { level: Level }) {
  return <span className={`lvl lvl-${level}`} title={LEVEL_TEXT[level]} aria-label={LEVEL_TEXT[level]} />;
}

function useHistory(metric: string) {
  return useQuery({ queryKey: ['health-history', metric], queryFn: () => api<HistoryPoint[]>(`/api/health/history?metric=${metric}&hours=72`), refetchInterval: 60000 });
}

function Metric({ title, level, value, detail, metric }: { title: string; level: Level; value: string; detail: React.ReactNode; metric?: string }) {
  const history = useHistory(metric ?? 'NONE');
  return (
    <article className={`metric lvl-border-${level}`}>
      <header>
        <Dot level={level} />
        <h3>{title}</h3>
      </header>
      <div className="metric-value">{value}</div>
      <div className="metric-detail muted">{detail}</div>
      {metric && <Sparkline points={history.data} bad={level === 'bad'} />}
    </article>
  );
}

export function Health() {
  const [hours, setHours] = useState(168);
  const { data, error, isFetching } = useQuery({
    queryKey: ['health', hours],
    queryFn: () => api<HealthResponse>(`/api/health?windowHours=${hours}`),
    refetchInterval: 30000,
    placeholderData: keepPreviousData,
  });
  const r = data?.report;
  const maxTotal = Math.max(1, ...(r?.failures.by_event.map((e) => e.total) ?? [1]));

  return (
    <>
      <PageHeader
        title={
          <span className="title-row">
            Health {data && <span className="chip">{data.environment || 'env'}</span>} {r && <span className={`badge lvl-badge-${r.level}`}>{LEVEL_TEXT[r.level]}</span>}
          </span>
        }
        subtitle="What is stuck, failing or waiting in NGC right now. Read-only view of the NGC database."
        actions={
          <>
            <select aria-label="Time window" value={hours} onChange={(e) => setHours(Number(e.target.value))}>
              {WINDOWS.map((w) => <option key={w.hours} value={w.hours}>{w.label}</option>)}
            </select>
            <span className="muted small">{isFetching ? 'Refreshing…' : r ? `Updated ${timeAgo(r.generated_at)}` : ''}</span>
          </>
        }
      />
      <ErrorNote error={error} />
      {!r && !error && <Empty>Reading the NGC database…</Empty>}
      {r && (
        <>
          <div className="traffic muted">
            {r.events_in_window} event{r.events_in_window === 1 ? '' : 's'} arrived in this window · last event {r.last_event_at ? `${timeAgo(r.last_event_at)} (${formatDateTime(r.last_event_at)})` : 'never'}
          </div>

          <Card title={r.issues.length ? `What needs attention (${r.issues.filter((i) => i.level !== 'info').length})` : 'What needs attention'}>
            {r.issues.length === 0 ? (
              <div className="all-clear">✓ Nothing needs attention.</div>
            ) : (
              <ul className="issues">
                {r.issues.map((i, n) => (
                  <li key={n} className={`issue lvl-issue-${i.level}`}>
                    <Dot level={i.level} /> {i.text}
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <div className="metrics">
            <Metric
              title="Stuck work"
              level={r.stuck.level}
              value={String(r.stuck.incoming_stuck)}
              metric="STUCK_INCOMING"
              detail={
                <>
                  incoming events processing &gt; {r.stuck.threshold_minutes} min
                  {r.stuck.oldest_incoming_minutes != null && <> · oldest {minutes(r.stuck.oldest_incoming_minutes)}</>}
                  <br />
                  {r.stuck.internal_stuck} callbacks stuck · {r.stuck.timed_overdue_recent} reminders overdue (7d)
                  <br />
                  <span className="small">Older leftovers, not counted: {r.stuck.incoming_stale} events, {r.stuck.timed_overdue_older} reminders</span>
                </>
              }
            />
            <Metric
              title="Failure rate"
              level={r.failures.level}
              value={pct(r.failures.failure_rate)}
              metric="FAILURE_RATE_24H"
              detail={
                <>
                  {r.failures.failed} of {r.failures.total} events failed
                  <br />
                  {r.failures.technical} technical · {r.failures.business} business
                </>
              }
            />
            <Metric
              title="Automatic retries"
              level={r.retries.level}
              value={String(r.retries.exhausted)}
              metric="RETRIES_EXHAUSTED"
              detail={
                <>
                  gave up after all attempts
                  <br />
                  {r.retries.successful} recovered · {r.retries.retrying} still retrying
                </>
              }
            />
            <Metric
              title="Dead letters"
              level={r.dead_letters.level}
              value={String(r.dead_letters.pending)}
              metric="DEAD_LETTER_PENDING"
              detail={
                <>
                  waiting for replay{r.dead_letters.oldest_pending_minutes != null && <> · oldest {minutes(r.dead_letters.oldest_pending_minutes)}</>}
                  <br />
                  {r.dead_letters.replayed} replayed OK · {r.dead_letters.exhausted} gave up
                </>
              }
            />
            <Metric
              title="CDS callbacks"
              level={r.funnel.level}
              value={pct(r.funnel.pdf_completed_rate)}
              metric="PDF_COMPLETED_RATE"
              detail={
                <>
                  of {r.funnel.sent_to_cds} sent to CDS got “PDF completed”
                  <br />
                  started {pct(r.funnel.pdf_started_rate)}
                  {r.funnel.median_seconds_to_pdf_started != null && <> · median {formatDuration(r.funnel.median_seconds_to_pdf_started)} to start</>}
                </>
              }
            />
          </div>

          <div className="two-col">
            <Card title="Failures by event">
              {r.failures.by_event.length === 0 ? (
                <Empty>No events in this window.</Empty>
              ) : (
                <table className="table">
                  <thead><tr><th>Event</th><th>Volume</th><th>Failed</th><th>Rate</th></tr></thead>
                  <tbody>
                    {r.failures.by_event.slice(0, 12).map((e) => (
                      <tr key={`${e.carrier}|${e.event_type}`}>
                        <td>
                          <span className="chip">{e.carrier}</span> <span className="mono">{e.event_type}</span>
                        </td>
                        <td>
                          <div className="vol" title={`${e.total} events`}>
                            <span style={{ width: `${(e.total / maxTotal) * 100}%` }} />
                            <em>{e.total}</em>
                          </div>
                        </td>
                        <td className={e.failed ? 'bad-text' : 'muted'}>{e.failed}{e.technical > 0 && <span className="muted small"> ({e.technical} tech)</span>}</td>
                        <td className={e.failure_rate != null && e.failure_rate >= 0.25 ? 'bad-text strong' : ''}>{pct(e.failure_rate)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Card>
            <div>
              <Card title="Top technical failures">
                {r.workers.length === 0 ? (
                  <Empty>No technical failures in this window.</Empty>
                ) : (
                  <ul className="plain">
                    {r.workers.map((w) => (
                      <li key={w.name} className="sched-row">
                        <span className="mono">{w.name}</span>
                        <strong>{w.count}</strong>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
              <Card title="New event types">
                {r.new_event_types.length === 0 ? (
                  <Empty>Nothing new in this window.</Empty>
                ) : (
                  <ul className="plain">
                    {r.new_event_types.slice(0, 15).map((n) => (
                      <li key={`${n.carrier}|${n.event_type}`} className="sched-row">
                        <span><span className="chip">{n.carrier}</span> <span className="mono">{n.event_type}</span></span>
                        <span className="muted small">{timeAgo(n.first_seen)}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </Card>
            </div>
          </div>
        </>
      )}
    </>
  );
}
