import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, json } from '../lib/api';
import { minimumDuration } from '../lib/runs';
import type { RunPreview } from '../lib/types';
import { ErrorNote, Field, Modal } from './ui';
import { TagPicker } from './TagPicker';

/** Runs every module's tests that match the selection; given a module (from the module page) only that module's. */
export function RunDialog({ module, onClose }: { module?: string; onClose: () => void }) {
  const navigate = useNavigate();
  const envs = useQuery({ queryKey: ['envs'], queryFn: () => api<{ code: string; name: string }[]>('/api/reference/environments') });
  const [env, setEnv] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [maxInFlight, setMaxInFlight] = useState('');
  const [rate, setRate] = useState('');
  const [confirmed, setConfirmed] = useState(false);

  const envCode = env || envs.data?.[0]?.code || '';
  const body = {
    module: module ?? null,
    env: envCode,
    tags: selected.join(',') || null,
    trigger: 'UI',
    maxInFlight: maxInFlight ? Number(maxInFlight) : null,
    eventsPerSecond: rate ? Number(rate) : null,
  };

  // how many events this selection sends, so a big run is never a surprise
  const preview = useQuery({
    queryKey: ['run-preview', body],
    queryFn: () => api<RunPreview>('/api/runs/preview', { method: 'POST', ...json(body) }),
    enabled: !!envCode,
    retry: false,
  });
  const p = preview.data;

  const start = useMutation({
    mutationFn: () => api<{ runKey: string }>('/api/runs', { method: 'POST', ...json({ ...body, confirmEvents: p?.requiresConfirmation ? p.events : null }) }),
    onSuccess: (r) => {
      onClose();
      navigate(`/runs/${r.runKey}`);
    },
  });

  const canRun = !!p && !start.isPending && (!p.requiresConfirmation || confirmed);

  return (
    <Modal title="Run tests" onClose={onClose}>
      <div className="form">
        {module && <p className="note">Only the test cases of the <strong>{module}</strong> module.</p>}
        <Field label="Environment" hint="Real emails and prints can be sent. Only allow-listed carriers can be injected.">
          <select value={envCode} onChange={(e) => { setEnv(e.target.value); setConfirmed(false); }}>
            {envs.data?.map((e) => <option key={e.code} value={e.code}>{e.name}</option>)}
          </select>
        </Field>
        <div className="field">
          <span className="field-label">Limit to (none = every enabled test case)</span>
          <TagPicker module={module} env={envCode} selected={selected} showPreview={false} onChange={(refs) => { setSelected(refs); setConfirmed(false); }} />
        </div>

        <div className={p?.requiresConfirmation ? 'preview-box warn' : 'preview-box'} aria-live="polite">
          {preview.isLoading && 'Counting events…'}
          {preview.error && <span className="bad-text">{(preview.error as Error).message}</span>}
          {p && (
            <>
              <strong>{p.events} event{p.events === 1 ? '' : 's'}</strong> from {p.cases} test case{p.cases === 1 ? '' : 's'} will be sent to <strong>{envCode}</strong>, taking at least {minimumDuration(p.minimumSeconds)}.
              {p.requiresConfirmation && (
                <label className="check confirm">
                  <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
                  I understand this sends {p.events} events to {envCode}
                </label>
              )}
            </>
          )}
        </div>

        <details className="advanced">
          <summary>Pacing (optional)</summary>
          <div className="grid2" style={{ marginTop: 8 }}>
            <Field label="Max unfinished events at once" hint="Default 25"><input type="number" min={1} value={maxInFlight} onChange={(e) => setMaxInFlight(e.target.value)} placeholder="25" /></Field>
            <Field label="Events per second" hint="Default 5"><input type="number" min={0.1} step={0.5} value={rate} onChange={(e) => setRate(e.target.value)} placeholder="5" /></Field>
          </div>
        </details>

        <ErrorNote error={start.error} />
        <footer className="form-actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={!canRun} onClick={() => start.mutate()}>
            {start.isPending ? 'Starting…' : p ? `Run ${p.events} event${p.events === 1 ? '' : 's'}` : 'Run'}
          </button>
        </footer>
      </div>
    </Modal>
  );
}
