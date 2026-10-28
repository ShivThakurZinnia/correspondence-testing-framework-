import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Card, Empty, ErrorNote, Modal, PageHeader, StageStepper, StatusBadge, StatusIcon } from '../components/ui';
import { api } from '../lib/api';
import { formatDateTime, shortKey } from '../lib/format';
import type { Artifact, EventDetail as Event } from '../lib/types';

export function EventDetail() {
  const { correlationId = '' } = useParams();
  const [artifactId, setArtifactId] = useState<number | null>(null);
  const { data: ev, error } = useQuery({
    queryKey: ['event', correlationId],
    queryFn: () => api<Event>(`/api/events/${correlationId}`),
    refetchInterval: (q) => (q.state.data && ['RUNNING', 'PENDING'].includes(q.state.data.status) ? 2000 : false),
  });
  if (error) return <ErrorNote error={error} />;
  if (!ev) return <Empty>Loading…</Empty>;

  const failed = ev.assertions.filter((a) => !a.passed);
  return (
    <>
      <PageHeader
        title={
          <span className="title-row">
            <StatusIcon status={ev.status} size={26} /> {ev.scenario_name} <StatusBadge status={ev.status} />
          </span>
        }
        subtitle={
          <>
            <span className="mono">{ev.correlation_id}</span> · {ev.carrier}{ev.plan_code ? ` · plan ${ev.plan_code}` : ''} · {ev.event_type} · topic <span className="mono">{ev.topic_name || '–'}</span>
          </>
        }
        actions={<Link className="btn" to={`/runs/${ev.run_key}`}>← Run #{shortKey(ev.run_key)}</Link>}
      />
      {ev.failure_summary && <div className="note error">{ev.failure_summary}</div>}

      <Card title="Progress">
        {ev.progress && <StageStepper progress={ev.progress} status={ev.status} />}
        <div className="facts inline">
          <div><span className="muted">NGC status</span><strong>{ev.final_incoming_status ?? '–'}</strong></div>
          <div><span className="muted">NGC unique id</span><strong className="mono">{ev.ngc_unique_id ?? '–'}</strong></div>
          <div><span className="muted">Published</span><strong>{formatDateTime(ev.published_at)}</strong></div>
          <div><span className="muted">Finished</span><strong>{formatDateTime(ev.finished_at)}</strong></div>
        </div>
      </Card>

      <Card title={`Assertions · ${ev.assertions.length - failed.length} passed, ${failed.length} failed`}>
        {ev.assertions.length === 0 ? (
          <Empty>No assertions were evaluated.</Empty>
        ) : (
          <table className="table">
            <thead><tr><th style={{ width: 28 }} /><th>Check</th><th>Expected</th><th>Actual</th><th>Note</th></tr></thead>
            <tbody>
              {ev.assertions.map((a, i) => (
                <tr key={i} className={a.passed ? '' : 'row-failed'}>
                  <td><StatusIcon status={a.passed ? 'PASSED' : 'FAILED'} size={16} /></td>
                  <td><span className="chip">{a.category.toLowerCase().replace('_', ' ')}</span> <span className="mono">{a.path}</span></td>
                  <td className="mono">{a.expected ?? ''}</td>
                  <td className="mono">{a.actual ?? ''}</td>
                  <td className="muted">{a.message ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="Timeline in NGC and CDS">
        {ev.timeline.length === 0 ? (
          <Empty>Nothing observed yet.</Empty>
        ) : (
          <ol className="timeline">
            {ev.timeline.map((t, i) => (
              <li key={i}>
                <span className="when">{formatDateTime(t.occurred_at)}</span>
                <span className="chip">{t.collection ?? t.source.toLowerCase()}</span>
                <strong>{t.event_type ?? ''}</strong>
                {t.status && <span className="muted"> {t.status}</span>}
                {t.target_role && <span className="chip">{t.target_role}</span>}
                {t.tango_job_id && <span className="muted"> job {t.tango_job_id}</span>}
              </li>
            ))}
          </ol>
        )}
      </Card>

      {ev.artifacts.length > 0 && (
        <Card title="Artifacts">
          <ul className="plain">
            {ev.artifacts.map((a) => (
              <li key={a.id}>
                <button className="link-btn" onClick={() => setArtifactId(a.id)}>{a.name}</button>
                <span className="muted"> {a.role.toLowerCase().replace('_', ' ')}</span>
              </li>
            ))}
          </ul>
        </Card>
      )}
      {artifactId !== null && <ArtifactModal id={artifactId} onClose={() => setArtifactId(null)} />}
    </>
  );
}

export function ArtifactModal({ id, onClose }: { id: number; onClose: () => void }) {
  const { data, error } = useQuery({ queryKey: ['artifact', id], queryFn: () => api<Artifact>(`/api/artifacts/${id}`) });
  return (
    <Modal title={data?.name ?? 'Artifact'} onClose={onClose} wide>
      <ErrorNote error={error} />
      {data && <pre className="code">{data.content}</pre>}
    </Modal>
  );
}
