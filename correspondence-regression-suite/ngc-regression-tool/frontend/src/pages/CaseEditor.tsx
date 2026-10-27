import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ExpectedBuilder } from '../components/ExpectedBuilder';
import { Card, ErrorNote, Field, PageHeader } from '../components/ui';
import { ArtifactModal } from './EventDetail';
import { api, json } from '../lib/api';
import { buildExpected, emptyExpected } from '../lib/expected';
import { invalidPlanCodes, parsePlanCodes } from '../lib/planCodes';
import type { CaseDetail, PayloadUpload, SaveResult, Tag, Topic } from '../lib/types';

const SLUG = /^[a-z0-9][a-z0-9-]{2,118}$/;

export function CaseEditor() {
  const { module = '', key } = useParams();
  const isNew = key === undefined || key === 'new';
  const navigate = useNavigate();
  const qc = useQueryClient();

  const existing = useQuery({ queryKey: ['case', module, key], queryFn: () => api<CaseDetail>(`/api/modules/${module}/cases/${key}`), enabled: !isNew });
  const tags = useQuery({ queryKey: ['tags'], queryFn: () => api<Tag[]>('/api/reference/tags') });
  const topics = useQuery({ queryKey: ['topics'], queryFn: () => api<Topic[]>('/api/reference/topics') });

  const [scenarioKey, setScenarioKey] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [kind, setKind] = useState<'SOR' | 'BPM'>('SOR');
  const [pattern, setPattern] = useState('');
  const [eventType, setEventType] = useState('');
  const [carrier, setCarrier] = useState('');
  const [planCodesText, setPlanCodesText] = useState('');
  const [timeout, setTimeoutSeconds] = useState('600');
  const [enabled, setEnabled] = useState(true);
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [expected, setExpected] = useState(JSON.stringify(buildExpected(emptyExpected()), null, 2));
  const [payload, setPayload] = useState<{ artifactId: number; correlationId?: string } | null>(null);
  const [golden, setGolden] = useState<number | null>(null);
  const [payloadText, setPayloadText] = useState('');
  const [note, setNote] = useState('');
  const [viewArtifact, setViewArtifact] = useState<number | null>(null);
  const [result, setResult] = useState<SaveResult | null>(null);

  useEffect(() => {
    const c = existing.data;
    if (!c) return;
    setScenarioKey(c.scenario_key);
    setName(c.name);
    setDescription(c.description ?? '');
    setKind(c.kafka_kind);
    setPattern(c.kafka_topic_pattern);
    setEventType(c.event_type);
    setCarrier(c.carrier);
    setPlanCodesText((c.plan_codes ?? '').split(',').filter(Boolean).join(', '));
    setTimeoutSeconds(String(c.timeout_seconds));
    setEnabled(c.enabled);
    setSelectedTags(c.tags ? c.tags.split(',') : []);
    setExpected(JSON.stringify(JSON.parse(c.expected_json), null, 2));
    setPayload({ artifactId: c.payload_artifact_id, correlationId: c.payload_correlation_id ?? undefined });
    setGolden(c.golden_artifact_id);
  }, [existing.data]);

  // BPM topics are per environment (zinnia.correspondence.<env>); show them as a pattern with {env}
  const patterns = Array.from(
    new Set((topics.data ?? []).filter((t) => t.kind === kind).map((t) => (kind === 'BPM' ? t.topic_name.replace(new RegExp(`\\.${t.env}$`), '.{env}') : t.topic_name))),
  );
  useEffect(() => {
    if (patterns.length && !patterns.includes(pattern)) setPattern(patterns[0]);
  }, [kind, topics.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const upload = useMutation({
    mutationFn: (text: string) => api<PayloadUpload>('/api/payloads?name=' + encodeURIComponent(name || 'event.json'), { method: 'POST', body: text }),
    onSuccess: (r) => {
      setPayload({ artifactId: r.artifactId!, correlationId: r.payloadCorrelationId });
      if (!eventType && r.eventType) setEventType(r.eventType);
      if (!carrier && r.carrier) setCarrier(r.carrier);
      if (!planCodesText.trim() && r.planCode) setPlanCodesText(r.planCode);
    },
  });
  const uploadGolden = useMutation({
    mutationFn: (text: string) => api<{ artifactId: number }>('/api/payloads/golden', { method: 'POST', body: text, headers: { 'Content-Type': 'application/xml' } }),
    onSuccess: (r) => setGolden(r.artifactId),
  });
  const save = useMutation({
    mutationFn: () =>
      api<SaveResult>(`/api/modules/${module}/cases/${scenarioKey}`, {
        method: 'PUT',
        ...json({
          name,
          description: description || null,
          kafkaKind: kind,
          kafkaTopicPattern: pattern,
          eventType,
          carrier,
          planCodes,
          payloadArtifactId: payload?.artifactId ?? null,
          goldenArtifactId: golden,
          expectedJson: expected,
          timeoutSeconds: Number(timeout),
          enabled,
          tags: selectedTags,
          changeNote: note || null,
        }),
      }),
    onSuccess: (r) => {
      setResult(r);
      qc.invalidateQueries({ queryKey: ['versions', module] });
      qc.invalidateQueries({ queryKey: ['version-cases', module] });
      qc.invalidateQueries({ queryKey: ['case', module, scenarioKey] });
      if (isNew) navigate(`/modules/${module}/cases/${scenarioKey}`, { replace: true });
    },
  });

  const readFile = (file: File | undefined, then: (text: string) => void) => file?.text().then(then);
  const toggleTag = (ref: string) => setSelectedTags((s) => (s.includes(ref) ? s.filter((x) => x !== ref) : [...s, ref]));
  const planCodes = parsePlanCodes(planCodesText);
  const badPlanCodes = invalidPlanCodes(planCodes);
  const keyOk = !isNew || SLUG.test(scenarioKey);
  const ready = keyOk && name && eventType && carrier && payload && pattern && badPlanCodes.length === 0 && planCodes.length <= 50 && selectedTags.some((t) => t.startsWith('client:')) && selectedTags.some((t) => t.startsWith('mapper:'));

  return (
    <>
      <PageHeader
        title={isNew ? 'New test case' : name || key}
        subtitle={isNew ? `In module ${module}` : `Revision ${existing.data?.revision ?? ''} · saving creates a new revision and a new module version`}
        actions={<Link className="btn" to={`/modules/${module}`}>← {module}</Link>}
      />
      <ErrorNote error={existing.error} />

      <Card title="1 · What to send">
        <div className="form grid2">
          {isNew && (
            <Field label="Scenario key" hint={keyOk || !scenarioKey ? 'Lowercase letters, digits and dashes, e.g. gili-policy-page-owner-print' : 'Use lowercase letters, digits and dashes (3+ chars)'}>
              <input value={scenarioKey} onChange={(e) => setScenarioKey(e.target.value)} placeholder="gili-policy-page-owner-print" />
            </Field>
          )}
          <Field label="Name"><input value={name} onChange={(e) => setName(e.target.value)} placeholder="GILI policy page – owner, print" /></Field>
          <Field label="Kafka topic type" hint="You choose the topic: SOR events or BPM events">
            <select value={kind} onChange={(e) => setKind(e.target.value as 'SOR' | 'BPM')}>
              <option value="SOR">SOR (zinnia.correspondence.sor)</option>
              <option value="BPM">BPM (zinnia.correspondence.&lt;env&gt;)</option>
            </select>
          </Field>
          <Field label="Topic">
            <select value={pattern} onChange={(e) => setPattern(e.target.value)}>
              {patterns.map((p) => <option key={p}>{p}</option>)}
            </select>
          </Field>
          <Field label="Event type"><input value={eventType} onChange={(e) => setEventType(e.target.value)} placeholder="policy.activation.completed" /></Field>
          <Field label="Carrier"><input value={carrier} onChange={(e) => setCarrier(e.target.value)} placeholder="GILI" /></Field>
          <Field
            label="Plan codes (comma separated)"
            hint={
              badPlanCodes.length
                ? `Not allowed: ${badPlanCodes.join(', ')} (letters, digits, dot, dash, underscore; max 30)`
                : planCodes.length > 1
                  ? `${planCodes.length} events will be played per run, one for each plan code`
                  : 'Leave empty to send the payload as uploaded. Several codes play the same test once per code.'
            }
          >
            <input value={planCodesText} onChange={(e) => setPlanCodesText(e.target.value)} placeholder="GCFIA901, GCFIA902, GCFIA903" aria-label="Plan codes" aria-invalid={badPlanCodes.length > 0} />
            {planCodes.length > 0 && <span className="chips">{planCodes.map((c) => <span key={c} className={badPlanCodes.includes(c) ? 'chip chip-bad' : 'chip'}>{c}</span>)}</span>}
          </Field>
          <Field label="Wait up to (seconds)"><input type="number" min={1} value={timeout} onChange={(e) => setTimeoutSeconds(e.target.value)} /></Field>
        </div>
        <Field label="Description"><input value={description} onChange={(e) => setDescription(e.target.value)} /></Field>

        <div className="field">
          <span className="field-label">Event payload (the Kafka message)</span>
          {payload && (
            <p className="ok-note">
              Payload stored (#{payload.artifactId}). Original correlation id <span className="mono">{payload.correlationId ?? 'n/a'}</span> is replaced by a fresh UUID on every run.{' '}
              <button type="button" className="link-btn" onClick={() => setViewArtifact(payload.artifactId)}>View</button>
            </p>
          )}
          <input type="file" accept=".json,application/json" aria-label="Upload event JSON" onChange={(e) => readFile(e.target.files?.[0], (t) => { setPayloadText(t); upload.mutate(t); })} />
          <textarea className="code-input" rows={5} placeholder="…or paste the event JSON here" value={payloadText} onChange={(e) => setPayloadText(e.target.value)} />
          <div>
            <button type="button" className="btn small" disabled={!payloadText.trim() || upload.isPending} onClick={() => upload.mutate(payloadText)}>Validate and store pasted JSON</button>
          </div>
          <ErrorNote error={upload.error} />
        </div>

        <div className="field">
          <span className="field-label">Mapper group (required)</span>
          <span className="hint">Which NGC mapper builds this letter. Schedules and runs pick test cases by mapper group, so a test case without one cannot be scheduled by group.</span>
          <div className="tag-options">
            {tags.data?.filter((t) => t.category === 'MAPPER').map((t) => {
              const ref = `mapper:${t.code}`;
              const on = selectedTags.includes(ref);
              return (
                <button type="button" key={ref} aria-pressed={on} title={t.description ?? undefined} className={`tag-option${on ? ' on' : ''}`}
                  onClick={() => setSelectedTags((s) => [...s.filter((x) => !x.startsWith('mapper:')), ...(on ? [] : [ref])])}>
                  <span className="tag-name">{t.label}</span>
                </button>
              );
            })}
          </div>
        </div>

        <div className="field">
          <span className="field-label">Tags (at least one client)</span>
          {(['CLIENT', 'PRIORITY'] as const).map((g) => (
            <div key={g} className="tag-group">
              <span className="tag-cat">{g.toLowerCase()}</span>
              {tags.data?.filter((t) => t.category === g).map((t) => {
                const ref = `${g.toLowerCase()}:${t.code}`;
                return <button type="button" key={ref} className={selectedTags.includes(ref) ? 'chip chip-btn on' : 'chip chip-btn'} aria-pressed={selectedTags.includes(ref)} onClick={() => toggleTag(ref)}>{t.label}</button>;
              })}
            </div>
          ))}
        </div>
      </Card>

      <Card title="2 · What must happen">
        <ExpectedBuilder value={expected} onChange={setExpected} />
        <div className="field">
          <span className="field-label">Golden XML (optional, for “matches golden” checks)</span>
          {golden && <p className="ok-note">Golden attached (#{golden}). <button type="button" className="link-btn" onClick={() => setViewArtifact(golden)}>View</button> <button type="button" className="link-btn" onClick={() => setGolden(null)}>Remove</button></p>}
          <input type="file" accept=".xml,application/xml,text/xml" aria-label="Upload golden XML" onChange={(e) => readFile(e.target.files?.[0], (t) => uploadGolden.mutate(t))} />
          <ErrorNote error={uploadGolden.error} />
        </div>
      </Card>

      <Card title="3 · Save">
        <div className="form">
          <label className="check"><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> Enabled (disabled cases are skipped by runs)</label>
          <Field label="Change note" hint="Shown in version history"><input value={note} onChange={(e) => setNote(e.target.value)} placeholder="What changed and why" /></Field>
          <ErrorNote error={save.error} />
          {result && (
            <div className={result.changed ? 'note ok' : 'note'}>
              {result.changed ? `Saved as revision ${result.revision}; module is now at v${result.moduleVersion}.` : `Nothing changed, so no new version was created (still v${result.moduleVersion}).`}
            </div>
          )}
          <footer className="form-actions">
            <button className="btn primary" disabled={!ready || save.isPending} onClick={() => save.mutate()}>{save.isPending ? 'Saving…' : 'Save test case'}</button>
          </footer>
        </div>
      </Card>
      {viewArtifact !== null && <ArtifactModal id={viewArtifact} onClose={() => setViewArtifact(null)} />}
    </>
  );
}
