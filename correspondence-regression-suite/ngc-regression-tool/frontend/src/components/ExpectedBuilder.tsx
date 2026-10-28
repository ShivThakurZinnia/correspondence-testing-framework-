import { useEffect, useState } from 'react';
import { CALLBACKS, emptyTarget, JOURNEY_STEPS, ROLES, STATUSES, buildExpected, parseExpected, GILI_POLICY_PAGE_TEMPLATE, type ExpectedState, type TargetExp } from '../lib/expected';

interface Props {
  value: string;
  onChange: (json: string) => void;
}

function Check({ label, on, set }: { label: string; on: boolean; set: (v: boolean) => void }) {
  return (
    <label className="check">
      <input type="checkbox" checked={on} onChange={(e) => set(e.target.checked)} /> {label}
    </label>
  );
}

function toggle(list: string[], item: string): string[] {
  return list.includes(item) ? list.filter((x) => x !== item) : [...list, item];
}

export function ExpectedBuilder({ value, onChange }: Props) {
  const parsed = parseExpected(value);
  const [jsonMode, setJsonMode] = useState(parsed === null && value.trim() !== '');
  useEffect(() => {
    if (parsed === null && value.trim() !== '') setJsonMode(true);
  }, [parsed, value]);

  const update = (s: ExpectedState) => onChange(JSON.stringify(buildExpected(s), null, 2));
  const state = parsed;

  return (
    <div className="expected">
      <div className="expected-bar">
        <div className="seg">
          <button type="button" className={!jsonMode ? 'on' : ''} disabled={state === null} onClick={() => setJsonMode(false)}>
            Form
          </button>
          <button type="button" className={jsonMode ? 'on' : ''} onClick={() => setJsonMode(true)}>
            JSON
          </button>
        </div>
        <button type="button" className="btn small" onClick={() => update(GILI_POLICY_PAGE_TEMPLATE)}>
          Use GILI policy page template
        </button>
      </div>

      {jsonMode || state === null ? (
        <>
          {state === null && value.trim() !== '' && <p className="hint">This uses options the form cannot show, so it is edited as JSON.</p>}
          <textarea className="code-input" rows={16} spellCheck={false} value={value} onChange={(e) => onChange(e.target.value)} aria-label="Expected JSON" />
        </>
      ) : (
        <div className="expected-form">
          <section>
            <h4>NGC final status</h4>
            <select value={state.incomingStatus} onChange={(e) => update({ ...state, incomingStatus: e.target.value })} aria-label="Final incoming event status">
              {STATUSES.map((s) => <option key={s}>{s}</option>)}
            </select>
          </section>

          <section>
            <h4>Parties that get a letter <span className="hint">(exactly these, no more)</span></h4>
            {state.targets.map((t, i) => {
              const set = (patch: Partial<TargetExp>) => update({ ...state, targets: state.targets.map((x, j) => (j === i ? { ...x, ...patch } : x)) });
              return (
                <div className="target-row" key={i}>
                  <select aria-label="Role" value={t.role} onChange={(e) => set({ role: e.target.value })}>{ROLES.map((r) => <option key={r}>{r}</option>)}</select>
                  <select aria-label="Target status" value={t.status} onChange={(e) => set({ status: e.target.value })}><option value="">status: any</option>{STATUSES.map((r) => <option key={r}>{r}</option>)}</select>
                  <select aria-label="Delivery" value={t.deliveryType} onChange={(e) => set({ deliveryType: e.target.value })}><option value="">delivery: any</option><option>PRINT</option><option>EMAIL</option><option>NONE</option></select>
                  <select aria-label="Email notification" value={t.emailNotification} onChange={(e) => set({ emailNotification: e.target.value })}><option value="">email: any</option><option value="YES">email YES</option><option value="NO">email NO</option></select>
                  <select aria-label="Agent" value={t.agentType} onChange={(e) => set({ agentType: e.target.value })}><option value="">agent CC: any</option><option>PRIMARYSERVICINGAGENT</option><option>PRIMARYWRITINGAGENT</option><option value="NA">none (NA)</option></select>
                  <input aria-label="Document type" placeholder="doc type e.g. POLPG" value={t.documentType} onChange={(e) => set({ documentType: e.target.value })} />
                  <Check label="XML file" on={t.xmlPresent} set={(v) => set({ xmlPresent: v })} />
                  <button type="button" className="icon-btn" aria-label="Remove party" onClick={() => update({ ...state, targets: state.targets.filter((_, j) => j !== i) })}>×</button>
                </div>
              );
            })}
            <button type="button" className="btn small" onClick={() => update({ ...state, targets: [...state.targets, emptyTarget()] })}>+ Add party</button>
          </section>

          <section>
            <h4>CDS</h4>
            <Check label="Message published to CDS" on={state.cdsPublished} set={(v) => update({ ...state, cdsPublished: v })} />
            <label className="inline">Exactly <input type="number" min={0} className="num" aria-label="CDS message count" value={state.cdsCount} onChange={(e) => update({ ...state, cdsCount: e.target.value })} /> message(s)</label>
          </section>

          <section>
            <h4>NGC journey steps seen <span className="hint">(hover a step to see what it means; NGC records none of them when CDS publishing is disabled)</span></h4>
            <div className="chips">{JOURNEY_STEPS.map((j) => <button type="button" key={j.code} title={j.help} className={state.journey.includes(j.code) ? 'chip chip-btn on' : 'chip chip-btn'} aria-pressed={state.journey.includes(j.code)} onClick={() => update({ ...state, journey: toggle(state.journey, j.code) })}>{j.code}</button>)}</div>
          </section>

          <section>
            <h4>Callbacks from CDS / Tango <span className="hint">(only the ones this scenario should wait for)</span></h4>
            <div className="chips">{CALLBACKS.map((j) => <button type="button" key={j} className={state.callbacks.includes(j) ? 'chip chip-btn on' : 'chip chip-btn'} aria-pressed={state.callbacks.includes(j)} onClick={() => update({ ...state, callbacks: toggle(state.callbacks, j) })}>{j}</button>)}</div>
          </section>

          <section>
            <h4>Must not happen</h4>
            <Check label="No technical failure recorded" on={state.noTech} set={(v) => update({ ...state, noTech: v })} />
            <Check label="Not captured as a dead letter" on={state.noDead} set={(v) => update({ ...state, noDead: v })} />
          </section>

          <section>
            <h4>Generated XML (S3)</h4>
            <Check label="Check the XML" on={state.xml.on} set={(v) => update({ ...state, xml: { ...state.xml, on: v } })} />
            {state.xml.on && (
              <div className="xml-opts">
                <Check label="File exists" on={state.xml.exists} set={(v) => update({ ...state, xml: { ...state.xml, exists: v } })} />
                <Check label="Valid against XSD" on={state.xml.xsdValid} set={(v) => update({ ...state, xml: { ...state.xml, xsdValid: v } })} />
                <Check label="Matches golden XML" on={state.xml.goldenCompare} set={(v) => update({ ...state, xml: { ...state.xml, goldenCompare: v } })} />
                <input aria-label="Ignore elements" placeholder="Ignore elements, comma separated (e.g. CREATION_DATE)" value={state.xml.ignore} onChange={(e) => update({ ...state, xml: { ...state.xml, ignore: e.target.value } })} />
                {state.targets.length > 1 && <input aria-label="XML role" placeholder="Role whose XML to check" value={state.xml.role} onChange={(e) => update({ ...state, xml: { ...state.xml, role: e.target.value } })} />}
              </div>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
