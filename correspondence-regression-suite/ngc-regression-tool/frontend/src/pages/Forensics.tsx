import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Card, Empty, ErrorNote, PageHeader } from '../components/ui';
import { api, ApiError } from '../lib/api';
import { formatDateTime, timeAgo } from '../lib/format';
import { ngcTone, OUTCOME_LABEL, params, WINDOWS } from '../lib/forensics';
import type { ForensicsCarrier, ForensicsEvent, ForensicsRejection, ForensicsSearch, ForensicsTree } from '../lib/types';

export function NgcStatus({ status }: { status: string | null }) {
  return status ? <span className={`badge tone-${ngcTone(status)}`}>{status}</span> : <span className="muted">–</span>;
}

interface Selection {
  carrier?: string;
  eventType?: string;
  outcome?: string;
  correlationId?: string;
  uniqueId?: string;
  policyNumber?: string;
}

const JUMP = [
  { key: 'correlationId', label: 'Correlation id' },
  { key: 'uniqueId', label: 'Unique id' },
  { key: 'policyNumber', label: 'Policy number' },
] as const;

function bucketsOf(t: { technical: number; business: number; stuck: number; ok: number }) {
  return [
    { outcome: 'technical', count: t.technical },
    { outcome: 'business', count: t.business },
    { outcome: 'stuck', count: t.stuck },
    { outcome: 'ok', count: t.ok },
  ].filter((b) => b.count > 0);
}

function BrowseTree({ tree, sel, onSelect }: { tree: ForensicsTree; sel: Selection; onSelect: (s: Selection) => void }) {
  const [filter, setFilter] = useState('');
  const [closed, setClosed] = useState<Set<string>>(new Set());
  const setOpen = (key: string, open: boolean) => setClosed((c) => { const n = new Set(c); if (open) n.delete(key); else n.add(key); return n; });
  const carriers = tree.carriers.filter((c) => c.carrier.toLowerCase().includes(filter.trim().toLowerCase()));

  const carrierRow = (c: ForensicsCarrier) => {
    const open = sel.carrier === c.carrier && !closed.has(`c:${c.carrier}`);
    const rejected = c.rejected_dead_letters + c.rejected_invalid;
    return (
      <li key={c.carrier}>
        <button className={`tree-row${sel.carrier === c.carrier && !sel.eventType ? ' selected' : ''}`} aria-expanded={open}
          onClick={() => { if (open) { setOpen(`c:${c.carrier}`, false); } else { setOpen(`c:${c.carrier}`, true); onSelect({ carrier: c.carrier }); } }}>
          <span className="caret" aria-hidden>{open ? '▾' : '▸'}</span>
          <span className="strong">{c.carrier}</span>
          <span className="tree-count muted">{c.total} events{c.failed > 0 && <span className="bad-text"> · {c.failed} failed</span>}</span>
        </button>
        {rejected > 0 && (
          <div className="tree-hint small muted">Rejected before processing: {c.rejected_dead_letters} dead letter{c.rejected_dead_letters === 1 ? '' : 's'}, {c.rejected_invalid} invalid</div>
        )}
        {open && (
          <ul className="tree-children">
            {c.event_types.map((t) => {
              const openType = sel.carrier === c.carrier && sel.eventType === t.event_type && !closed.has(`e:${c.carrier}|${t.event_type}`);
              return (
                <li key={t.event_type}>
                  <button className={`tree-row${sel.eventType === t.event_type && sel.carrier === c.carrier && !sel.outcome ? ' selected' : ''}`} aria-expanded={openType}
                    onClick={() => { if (openType) { setOpen(`e:${c.carrier}|${t.event_type}`, false); } else { setOpen(`e:${c.carrier}|${t.event_type}`, true); onSelect({ carrier: c.carrier, eventType: t.event_type }); } }}>
                    <span className="caret" aria-hidden>{openType ? '▾' : '▸'}</span>
                    <span className="mono">{t.event_type}</span>
                    <span className="tree-count muted">{t.total}{t.failed > 0 && <span className="bad-text"> · {t.failed} failed</span>}</span>
                  </button>
                  {openType && (
                    <ul className="tree-children">
                      {bucketsOf(t).map((b) => (
                        <li key={b.outcome}>
                          <button className={`tree-row${sel.outcome === b.outcome ? ' selected' : ''}`}
                            onClick={() => onSelect({ carrier: c.carrier, eventType: t.event_type, outcome: b.outcome })}>
                            <span className="caret" aria-hidden>·</span>
                            <span>{OUTCOME_LABEL[b.outcome]}</span>
                            <span className="tree-count muted">{b.count}</span>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </li>
    );
  };

  return (
    <>
      <input aria-label="Find a carrier" list="carriers" placeholder="Type a carrier, e.g. GILI" value={filter} onChange={(e) => setFilter(e.target.value)} />
      <datalist id="carriers">{tree.carriers.map((c) => <option key={c.carrier} value={c.carrier} />)}</datalist>
      {carriers.length === 0 ? <Empty>No carrier matches in this window.</Empty> : <ul className="tree" role="tree">{carriers.map(carrierRow)}</ul>}
    </>
  );
}

function RejectedCard({ rows }: { rows: ForensicsRejection[] }) {
  if (rows.length === 0) return null;
  return (
    <Card title={`Rejected before processing (${rows.length})`}>
      <p className="muted small">These events never got an incoming event row, so there is no status history beyond what is shown here.</p>
      <table className="table">
        <thead><tr><th>When</th><th>Kind</th><th>Event</th><th>Policy</th><th>Reason</th><th /></tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={`${r.correlation_id}-${i}`}>
              <td title={formatDateTime(r.at)}>{timeAgo(r.at)}</td>
              <td><span className="chip">{r.kind === 'DEAD_LETTER' ? 'Dead letter' : 'Invalid'}</span></td>
              <td>{r.carrier && <span className="chip">{r.carrier}</span>} <span className="mono">{r.event_type ?? '–'}</span></td>
              <td className="mono">{r.policy_number ?? '–'}</td>
              <td className="small">{r.reason ?? '–'}{r.status && <span className="muted"> · {r.status}</span>}</td>
              <td>{r.correlation_id && <Link to={`/forensics/events/${encodeURIComponent(r.correlation_id)}`}>History</Link>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

function EventRow({ e }: { e: ForensicsEvent }) {
  return (
    <tr>
      <td title={formatDateTime(e.created_at)}>{timeAgo(e.created_at)}</td>
      <td className="mono">{e.policy_number ?? '–'}{e.plan_code && <span className="chip plan-chip">{e.plan_code}</span>}</td>
      <td>
        {e.carrier && <span className="chip">{e.carrier}</span>} <span className="mono">{e.event_type ?? '–'}</span>
        {e.targets.length > 0 && <div className="small muted">{e.targets.map((t) => `${t.role ?? '?'} ${t.status ?? ''}`.trim()).join(' · ')}</div>}
      </td>
      <td>
        <NgcStatus status={e.status} />
        {e.error_category && <div className="small muted">{e.error_category}</div>}
        {e.retry_in_progress && <div className="small muted">retrying</div>}
      </td>
      <td className="small">{e.error ?? ''}</td>
      <td><Link to={`/forensics/events/${encodeURIComponent(e.correlation_id)}?uniqueId=${encodeURIComponent(e.unique_id)}`}>History</Link></td>
    </tr>
  );
}

function Results({ sel, hours }: { sel: Selection; hours: number }) {
  const base = { ...sel, hours: String(hours), outcome: sel.outcome ?? 'any' };
  const query = useInfiniteQuery({
    queryKey: ['forensics-events', base],
    queryFn: ({ pageParam }) => api<ForensicsSearch>(`/api/forensics/events?${params({ ...base, before: pageParam || undefined })}`),
    initialPageParam: '',
    getNextPageParam: (last) => (last.has_more ? last.next_before ?? undefined : undefined),
  });
  const pages = query.data?.pages ?? [];
  const events = pages.flatMap((p) => p.events);
  return (
    <>
      <ErrorNote error={query.error} />
      {query.isLoading && <Empty>Searching NGC…</Empty>}
      {pages[0] && <RejectedCard rows={pages[0].rejected} />}
      {pages[0] && (
        <Card title={`Events (${events.length}${query.hasNextPage ? '+' : ''})`}>
          {events.length === 0 ? (
            <Empty>No events match.</Empty>
          ) : (
            <table className="table">
              <thead><tr><th>When</th><th>Policy</th><th>Event</th><th>Status</th><th>Error</th><th /></tr></thead>
              <tbody>{events.map((e) => <EventRow key={e.unique_id} e={e} />)}</tbody>
            </table>
          )}
          {query.hasNextPage && (
            <div className="more"><button className="btn" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>{query.isFetchingNextPage ? 'Loading…' : 'Load more'}</button></div>
          )}
        </Card>
      )}
    </>
  );
}

export function Forensics() {
  const [search, setSearch] = useSearchParams();
  const hours = Number(search.get('hours')) || 24;
  const sel: Selection = {
    carrier: search.get('carrier') ?? undefined,
    eventType: search.get('eventType') ?? undefined,
    outcome: search.get('outcome') ?? undefined,
    correlationId: search.get('correlationId') ?? undefined,
    uniqueId: search.get('uniqueId') ?? undefined,
    policyNumber: search.get('policyNumber') ?? undefined,
  };
  const [kind, setKind] = useState<(typeof JUMP)[number]['key']>('correlationId');
  const [jump, setJump] = useState('');
  const go = (next: Selection) => setSearch(params({ hours: String(hours), ...next }));
  const tree = useQuery({ queryKey: ['forensics-tree', hours], queryFn: () => api<ForensicsTree>(`/api/forensics/tree?hours=${hours}`), staleTime: 60000, retry: false });
  const notConfigured = tree.error instanceof ApiError && tree.error.status === 503;
  const exact = sel.correlationId || sel.uniqueId || sel.policyNumber;
  const crumbs = [
    sel.carrier && { label: sel.carrier, to: { carrier: sel.carrier } },
    sel.eventType && { label: sel.eventType, to: { carrier: sel.carrier, eventType: sel.eventType } },
    sel.outcome && { label: OUTCOME_LABEL[sel.outcome] ?? sel.outcome, to: sel },
  ].filter(Boolean) as { label: string; to: Selection }[];

  return (
    <>
      <PageHeader
        title="Forensics"
        subtitle="Look back at what NGC did with an event: browse by carrier and event type, or jump to one event. Read-only."
        actions={
          <select aria-label="Time window" value={hours} onChange={(e) => setSearch(params({ ...Object.fromEntries(search), hours: e.target.value }))}>
            {WINDOWS.map((w) => <option key={w.hours} value={w.hours}>{w.label}</option>)}
          </select>
        }
      />
      <form className="filters" onSubmit={(e) => { e.preventDefault(); if (jump.trim()) go({ [kind]: jump.trim() }); }}>
        <select aria-label="Jump to" value={kind} onChange={(e) => setKind(e.target.value as typeof kind)}>
          {JUMP.map((j) => <option key={j.key} value={j.key}>{j.label}</option>)}
        </select>
        <input aria-label="Jump to value" placeholder="Exact value, any time" value={jump} onChange={(e) => setJump(e.target.value)} />
        <button className="btn primary">Find</button>
      </form>
      {notConfigured ? (
        <ErrorNote error={tree.error} />
      ) : (
        <div className="forensics">
          <Card title="Browse">
            <ErrorNote error={tree.error} />
            {tree.isLoading && <Empty>Reading the NGC database…</Empty>}
            {tree.data && <BrowseTree tree={tree.data} sel={sel} onSelect={go} />}
          </Card>
          <div>
            {!sel.carrier && !exact ? (
              <Empty>Pick a carrier on the left, or jump to an event by id or policy number.</Empty>
            ) : (
              <>
                <nav className="crumbs" aria-label="Selection">
                  {exact ? (
                    <span>{JUMP.find((j) => sel[j.key])?.label}: <span className="mono">{sel.correlationId ?? sel.uniqueId ?? sel.policyNumber}</span></span>
                  ) : (
                    crumbs.map((c, i) => (
                      <span key={c.label}>{i > 0 && ' › '}<button className="link" onClick={() => go(c.to)}>{c.label}</button></span>
                    ))
                  )}
                  <span className="muted small"> · {exact ? 'any time' : WINDOWS.find((w) => w.hours === hours)?.label ?? `last ${hours}h`}</span>
                </nav>
                <Results sel={sel} hours={hours} />
              </>
            )}
          </div>
        </div>
      )}
    </>
  );
}
