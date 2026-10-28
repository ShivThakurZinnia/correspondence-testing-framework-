import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { TagPicker } from '../components/TagPicker';
import { Card, Empty, ErrorNote, Field, Modal, PageHeader } from '../components/ui';
import { api, json } from '../lib/api';
import { useToast } from '../lib/toast';
import { CRON_PRESETS, describeCron, isSixFieldCron } from '../lib/cron';
import { formatDateTime, timeAgo } from '../lib/format';
import { parseTagExpression, tagExpression } from '../lib/tags';
import type { Schedule, ScheduleInput } from '../lib/types';

const ZONES = ['UTC', 'Asia/Kolkata', 'America/New_York', 'America/Chicago', 'America/Los_Angeles', 'Europe/London'];

export function Schedules() {
  const qc = useQueryClient();
  const toast = useToast();
  const [editing, setEditing] = useState<Schedule | 'new' | null>(null);
  const schedules = useQuery({ queryKey: ['schedules'], queryFn: () => api<Schedule[]>('/api/schedules'), refetchInterval: 5000 });
  const refresh = () => qc.invalidateQueries({ queryKey: ['schedules'] });
  const toggle = useMutation({ mutationFn: (s: Schedule) => api(`/api/schedules/${s.id}/enabled`, { method: 'PUT', ...json({ enabled: !s.enabled }) }), onSuccess: refresh });
  const remove = useMutation({ mutationFn: (s: Schedule) => api(`/api/schedules/${s.id}`, { method: 'DELETE' }), onSuccess: refresh });

  return (
    <>
      <PageHeader
        title="Schedules"
        subtitle="Runs that start by themselves, at fixed times per environment. A schedule can be edited or deleted only while it is not executing."
        actions={<button className="btn primary" onClick={() => setEditing('new')}>+ New schedule</button>}
      />
      <ErrorNote error={schedules.error ?? toggle.error ?? remove.error} />
      <Card>
        {schedules.data?.length ? (
          <table className="table">
            <thead><tr><th>Name</th><th>What</th><th>When</th><th>Next run</th><th>Last started</th><th>Enabled</th><th /></tr></thead>
            <tbody>
              {schedules.data.map((s) => (
                <tr key={s.id} className={s.enabled ? '' : 'row-disabled'}>
                  <td className="strong">{s.name}{s.executing && <span className="chip run-chip">executing</span>}</td>
                  <td>{s.tag_expression ? s.tag_expression.split(',').join(' · ') : 'All tests'} <span className="chip">{s.env}</span><div className="muted small">{s.module ? `${s.module} module only · ` : ''}latest tests</div></td>
                  <td>{describeCron(s.cron_expression)}<div className="muted small">{s.timezone} · {s.overlap_policy.toLowerCase()} if still running{s.max_in_flight || s.events_per_second ? ` · paced ${s.max_in_flight ?? 25} in flight, ${s.events_per_second ?? 5}/s` : ''}</div></td>
                  <td title={formatDateTime(s.next_fire_at)}>{s.enabled ? timeAgo(s.next_fire_at) : '–'}</td>
                  <td>{timeAgo(s.last_fired_at)}</td>
                  <td>
                    <label className="switch" title={s.enabled ? 'Disable' : 'Enable'}>
                      <input type="checkbox" role="switch" aria-label={`Enable ${s.name}`} checked={s.enabled} onChange={() => toggle.mutate(s)} />
                      <span />
                    </label>
                  </td>
                  <td className="actions">
                    <button className="btn small" disabled={s.executing} title={s.executing ? 'Executing right now' : ''} onClick={() => setEditing(s)}>Edit</button>
                    <button className="btn small danger" disabled={s.executing || remove.isPending} title={s.executing ? 'Executing right now' : ''} onClick={() => window.confirm(`Delete schedule "${s.name}"? Past runs stay in history.`) && remove.mutate(s)}>Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <Empty>No schedules yet. Create one to run a module daily.</Empty>
        )}
      </Card>
      {editing && <ScheduleForm schedule={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={(scheduleId, startFirstRun) => {
        setEditing(null);
        refresh();
        if (!startFirstRun || scheduleId === undefined) return;
        const starting = toast.show({ text: 'Schedule saved. Starting the first run…', ms: 0 });
        api<{ runKey: string }>(`/api/schedules/${scheduleId}/run`, { method: 'POST' })
          .then((r) => toast.show({ text: 'First run started.', tone: 'ok', linkTo: `/runs/${r.runKey}`, linkText: 'View run', ms: 15000 }))
          .catch((e: unknown) => toast.show({ text: `Schedule saved, but the first run did not start: ${e instanceof Error ? e.message : String(e)}`, tone: 'bad', ms: 0 }))
          .finally(() => { toast.dismiss(starting); refresh(); });
      }} />}
    </>
  );
}

function ScheduleForm({ schedule, onClose, onSaved }: { schedule: Schedule | null; onClose: () => void; onSaved: (scheduleId?: number, startFirstRun?: boolean) => void }) {
  const envs = useQuery({ queryKey: ['envs'], queryFn: () => api<{ code: string; name: string }[]>('/api/reference/environments') });
  const [form, setForm] = useState({
    name: schedule?.name ?? '',
    env: schedule?.env ?? '',
    cron: schedule?.cron_expression ?? CRON_PRESETS[0].cron,
    timezone: schedule?.timezone ?? 'UTC',
    overlap: schedule?.overlap_policy ?? 'SKIP',
    maxInFlight: schedule?.max_in_flight ? String(schedule.max_in_flight) : '',
    rate: schedule?.events_per_second ? String(schedule.events_per_second) : '',
  });
  const [runNow, setRunNow] = useState(false);
  const [selectedTags, setSelectedTags] = useState<string[]>(() => parseTagExpression(schedule?.tag_expression));
  const envCode = form.env || envs.data?.[0]?.code || '';
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });

  const save = useMutation({
    mutationFn: () => {
      const body: ScheduleInput = {
        name: form.name,
        module: schedule?.module ?? null,
        versionNo: null,
        env: envCode,
        tags: tagExpression(selectedTags),
        cron: form.cron.trim(),
        timezone: form.timezone,
        overlapPolicy: form.overlap,
        maxInFlight: form.maxInFlight ? Number(form.maxInFlight) : null,
        eventsPerSecond: form.rate ? Number(form.rate) : null,
      };
      if (schedule) {
        return api(`/api/schedules/${schedule.id}`, { method: 'PUT', ...json(body) }).then(() => undefined);
      }
      return api<{ id: number }>('/api/schedules', { method: 'POST', ...json(body) }).then((created) => created.id);
    },
    // the first run starts in the background after the form closes, so saving never waits for it
    onSuccess: (id) => onSaved(id, !schedule && runNow),
  });

  return (
    <Modal title={schedule ? `Edit ${schedule.name}` : 'New schedule'} onClose={onClose} wide>
      <div className="form">
        <Field label="Name"><input value={form.name} onChange={set('name')} placeholder="nightly-anniversary-dev" /></Field>
        <div className="grid2">
          <Field label="Environment">
            <select value={form.env || envs.data?.[0]?.code || ''} onChange={set('env')}>{envs.data?.map((e) => <option key={e.code} value={e.code}>{e.name}</option>)}</select>
          </Field>
        </div>
        <div className="field">
          <span className="field-label">What to run</span>
          <span className="hint">Always the latest tests. Pick one or more mappers (and optionally narrow by client or priority); the numbers are events, one per plan code.</span>
          <TagPicker module={schedule?.module ?? undefined} env={envCode} selected={selectedTags} onChange={setSelectedTags} />
        </div>
        <Field label="When" hint={isSixFieldCron(form.cron) ? `${describeCron(form.cron)} (second minute hour day month weekday)` : 'Needs 6 fields: second minute hour day month weekday'}>
          <select value={CRON_PRESETS.some((p) => p.cron === form.cron) ? form.cron : ''} onChange={(e) => e.target.value && setForm({ ...form, cron: e.target.value })}>
            <option value="">Custom…</option>
            {CRON_PRESETS.map((p) => <option key={p.cron} value={p.cron}>{p.label}</option>)}
          </select>
          <input className="mono" value={form.cron} onChange={set('cron')} aria-label="Cron expression" />
        </Field>
        <div className="grid2">
          <Field label="Time zone">
            <input list="zones" value={form.timezone} onChange={set('timezone')} />
            <datalist id="zones">{ZONES.map((z) => <option key={z} value={z} />)}</datalist>
          </Field>
          <Field label="If the previous run is still going">
            <select value={form.overlap} onChange={set('overlap')}>
              <option value="SKIP">Skip this time</option>
              <option value="QUEUE">Wait and start after</option>
            </select>
          </Field>
        </div>
        <details className="advanced" open={!!(form.maxInFlight || form.rate)}>
          <summary>Pacing for the whole module (optional)</summary>
          <div className="grid2" style={{ marginTop: 8 }}>
            <Field label="Max unfinished events at once" hint="Default 25"><input type="number" min={1} value={form.maxInFlight} onChange={set('maxInFlight')} placeholder="25" /></Field>
            <Field label="Events per second" hint="Default 5"><input type="number" min={0.1} step={0.5} value={form.rate} onChange={set('rate')} placeholder="5" /></Field>
          </div>
        </details>
        {!schedule && (
          <label className="check">
            <input type="checkbox" checked={runNow} onChange={(e) => setRunNow(e.target.checked)} />
            <span><strong>Run now</strong> <span className="hint">also start the first run immediately, then follow the schedule</span></span>
          </label>
        )}
        <ErrorNote error={save.error} />
        <footer className="form-actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={save.isPending || !form.name || !isSixFieldCron(form.cron) || !(form.env || envs.data?.length)} onClick={() => save.mutate()}>{save.isPending ? 'Saving…' : !schedule && runNow ? 'Save and run now' : 'Save'}</button>
        </footer>
      </div>
    </Modal>
  );
}
