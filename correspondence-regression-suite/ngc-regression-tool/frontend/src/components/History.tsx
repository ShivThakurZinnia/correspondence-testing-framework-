import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../lib/api';
import { formatDateTime } from '../lib/format';
import { revisionChanges } from '../lib/history';
import type { CaseDetail, DiffRow, ModuleVersion } from '../lib/types';
import { Empty, ErrorNote, Modal } from './ui';

/** Read-only revision history of one test case. */
export function CaseHistory({ module, scenarioKey, onClose }: { module: string; scenarioKey: string; onClose: () => void }) {
  const { data, error } = useQuery({ queryKey: ['revisions', module, scenarioKey], queryFn: () => api<CaseDetail[]>(`/api/modules/${module}/cases/${scenarioKey}/revisions`) });
  const [open, setOpen] = useState<number | null>(null);
  return (
    <Modal title={`History · ${scenarioKey}`} onClose={onClose} wide>
      <ErrorNote error={error} />
      {!data && !error && <Empty>Loading…</Empty>}
      {data && (
        <ul className="history-list">
          {data.map((rev, i) => (
            <li key={rev.revision}>
              <div className="history-head">
                <strong>Revision {rev.revision}</strong>
                {i === 0 && <span className="chip st-approved">current</span>}
                <span className="muted">{rev.created_by} · {formatDateTime(rev.created_at)}</span>
                {rev.change_note && <span>— {rev.change_note}</span>}
              </div>
              <ul className="changes">
                {revisionChanges(data[i + 1], rev).map((c) => <li key={c}>{c}</li>)}
              </ul>
              <button className="link-btn small" onClick={() => setOpen(open === rev.revision ? null : rev.revision)}>
                {open === rev.revision ? 'Hide expected outcome' : 'Show expected outcome'}
              </button>
              {open === rev.revision && <pre className="code">{JSON.stringify(JSON.parse(rev.expected_json), null, 2)}</pre>}
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}

function Changes({ module, from, to }: { module: string; from: number; to: number }) {
  const { data } = useQuery({ queryKey: ['diff', module, from, to], queryFn: () => api<DiffRow[]>(`/api/modules/${module}/versions/${from}/diff/${to}`) });
  if (!data) return <span className="muted small">Loading…</span>;
  if (data.length === 0) return <span className="muted small">No test case changed</span>;
  return (
    <ul className="changes">
      {data.map((d) => <li key={d.scenario_key}><span className="mono">{d.scenario_key}</span> — {d.change.toLowerCase()}</li>)}
    </ul>
  );
}

/** Read-only list of module versions (each save of any test case makes one). */
export function ModuleHistory({ module, onClose }: { module: string; onClose: () => void }) {
  const { data, error } = useQuery({ queryKey: ['versions', module], queryFn: () => api<ModuleVersion[]>(`/api/modules/${module}/versions`) });
  return (
    <Modal title={`Version history · ${module}`} onClose={onClose} wide>
      <p className="hint">A new version of the module is recorded every time a test case is added or changed. This list is for reference only.</p>
      <ErrorNote error={error} />
      {data && (
        <ul className="history-list">
          {data.map((v, i) => (
            <li key={v.id}>
              <div className="history-head">
                <strong>v{v.version}</strong>
                {i === 0 && <span className="chip st-approved">latest</span>}
                <span className="muted">{v.created_by} · {formatDateTime(v.created_at)} · {v.case_count} test case{v.case_count === 1 ? '' : 's'}</span>
                <span>— {v.change_note}</span>
              </div>
              {i < data.length - 1 && <Changes module={module} from={data[i + 1].version_no} to={v.version_no} />}
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}
