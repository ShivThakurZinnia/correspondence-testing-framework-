import { useQuery } from '@tanstack/react-query';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { Card, Empty, ErrorNote, PageHeader } from '../components/ui';
import { api } from '../lib/api';
import { formatDateTime, timeAgo } from '../lib/format';
import { factSheet } from '../lib/forensics';
import { useToast } from '../lib/toast';
import type { ForensicsHistory } from '../lib/types';
import { NgcStatus } from './Forensics';

export function ForensicsDetail() {
  const { correlationId = '' } = useParams();
  const [search, setSearch] = useSearchParams();
  const attempt = search.get('uniqueId');
  const toast = useToast();
  const history = useQuery({
    queryKey: ['forensics-history', correlationId, attempt],
    queryFn: () => api<ForensicsHistory>(`/api/forensics/events/${encodeURIComponent(correlationId)}${attempt ? `?uniqueId=${encodeURIComponent(attempt)}` : ''}`),
    retry: false,
  });
  const h = history.data;
  const copy = async () => {
    if (!h) return;
    try {
      await navigator.clipboard.writeText(factSheet(h));
      toast.show({ text: 'Fact sheet copied.', tone: 'ok', ms: 4000 });
    } catch {
      toast.show({ text: 'Could not copy; select the timeline text instead.', tone: 'bad' });
    }
  };

  return (
    <>
      <PageHeader
        title="Event history"
        subtitle={<>Correlation id <span className="mono">{correlationId}</span> · <Link to="/forensics">Back to Forensics</Link></>}
        actions={<button className="btn" onClick={copy} disabled={!h}>Copy fact sheet</button>}
      />
      <ErrorNote error={history.error} />
      {history.isLoading && <Empty>Reading the NGC database…</Empty>}
      {h && (
        <>
          {h.attempt_count > 1 && (
            <div className="filters">
              <label>
                Attempt{' '}
                <select aria-label="Attempt" value={h.selected_attempt ?? 'all'} onChange={(e) => setSearch(e.target.value ? { uniqueId: e.target.value } : {})}>
                  <option value="all">All {h.attempt_count} attempts</option>
                  {[...h.attempts].reverse().map((a) => (
                    <option key={a.unique_id} value={a.unique_id}>{formatDateTime(a.created_at)} · {a.status ?? '?'}{a.error_category ? ` (${a.error_category})` : ''}</option>
                  ))}
                </select>
              </label>
              <span className="muted small">{h.attempt_count} processing attempts share this correlation id.</span>
            </div>
          )}

          <Card title="Where it got to">
            <div className="stepper" role="list" aria-label="Stages">
              {h.stages.map((s) => (
                <div key={s.code} role="listitem" className={`step ${s.state === 'done' ? 'done' : s.state === 'failed' ? 'failed' : ''}`} title={`${s.label}: ${s.state}${s.at ? ` · ${formatDateTime(s.at)}` : ''}`}>
                  <span className="dot" />
                  <span className="label">{s.label}</span>
                </div>
              ))}
            </div>
          </Card>

          {h.markers.length > 0 && (
            <Card title="What this history cannot tell you">
              <ul className="marker-list">{h.markers.map((m) => <li key={m}>{m}</li>)}</ul>
            </Card>
          )}

          <Card title={h.selected_attempt ? 'This attempt' : `Attempts (${h.attempts.length})`}>
            {h.attempts.length === 0 ? (
              <Empty>No incoming event exists for this correlation id: it was rejected before processing.</Empty>
            ) : (
              <table className="table">
                <thead><tr><th>Received</th><th>Event</th><th>Policy</th><th>Status</th><th>Targets</th><th>Error</th></tr></thead>
                <tbody>
                  {h.attempts.filter((a) => !h.selected_attempt || a.unique_id === h.selected_attempt).map((a) => (
                    <tr key={a.unique_id}>
                      <td title={formatDateTime(a.created_at)}>{timeAgo(a.created_at)}</td>
                      <td>{a.carrier && <span className="chip">{a.carrier}</span>} <span className="mono">{a.event_type ?? '–'}</span></td>
                      <td className="mono">{a.policy_number ?? '–'}{a.plan_code && <span className="chip plan-chip">{a.plan_code}</span>}</td>
                      <td><NgcStatus status={a.status} />{a.error_category && <div className="small muted">{a.error_category}</div>}</td>
                      <td className="small">{a.targets.map((t) => (
                        <div key={`${t.role}-${t.status}`}>{t.role ?? '?'} · {t.status ?? '–'}{t.delivery_type ? ` · ${t.delivery_type}` : ''}{t.error && <span className="muted"> · {t.error}</span>}</div>
                      ))}</td>
                      <td className="small">{a.error ?? ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>

          {h.retries.length > 0 && (
            <Card title="Automatic retries">
              <table className="table">
                <thead><tr><th>Worker</th><th>Status</th><th>Attempts</th><th>Last attempt</th><th>Next eligible</th></tr></thead>
                <tbody>
                  {h.retries.map((r, i) => (
                    <tr key={i}>
                      <td className="mono">{r.worker ?? 'not recorded'}</td>
                      <td><NgcStatus status={r.status} /></td>
                      <td>{r.attempts}</td>
                      <td>{formatDateTime(r.last_attempt_at)}</td>
                      <td>{r.next_eligible_at ? formatDateTime(r.next_eligible_at) : '–'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          )}

          <Card title={`Timeline (${h.timeline.length})`}>
            <table className="table">
              <thead><tr><th>When</th><th>Source</th><th>What</th><th>Status</th><th>Role</th><th>Detail</th></tr></thead>
              <tbody>
                {h.timeline.map((e, i) => (
                  <tr key={i}>
                    <td title={e.at ?? ''}>{formatDateTime(e.at)}</td>
                    <td><span className="chip">{e.collection}</span></td>
                    <td className="mono">{e.type ?? '–'}</td>
                    <td><NgcStatus status={e.status} /></td>
                    <td>{e.role ?? ''}</td>
                    <td className="small">{e.detail ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        </>
      )}
    </>
  );
}
