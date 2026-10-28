import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { CaseHistory, ModuleHistory } from '../components/History';
import { RunDialog } from '../components/RunDialog';
import { Card, Empty, ErrorNote, PageHeader } from '../components/ui';
import { api } from '../lib/api';
import type { ModuleRow, ModuleVersion, TagCounts, VersionCase } from '../lib/types';

const PAGE = 50;

export function Modules() {
  const { module: selected } = useParams();
  const modules = useQuery({ queryKey: ['modules'], queryFn: () => api<ModuleRow[]>('/api/modules') });
  const current = selected ?? null;
  return (
    <>
      <PageHeader title="Test modules" subtitle="All test cases are listed together; a module is only a folder to keep them in. Click History on a test case to see how it changed." />
      <ErrorNote error={modules.error} />
      <div className="tabs">
        <Link to="/modules" className={current === null ? 'tab on' : 'tab'}>All</Link>
        {modules.data?.map((m) => (
          <Link key={m.code} to={`/modules/${m.code}`} className={m.code === current ? 'tab on' : 'tab'}>{m.name}</Link>
        ))}
      </div>
      <ModuleView module={current} />
    </>
  );
}

/** One module's test cases, or with no module every module's newest test cases (what schedules and runs draw from). */
function ModuleView({ module }: { module: string | null }) {
  const [running, setRunning] = useState(false);
  const [moduleHistory, setModuleHistory] = useState(false);
  const [history, setHistory] = useState<{ module: string; key: string } | null>(null);
  const [search, setSearch] = useState('');
  const [mapper, setMapper] = useState('');
  const [page, setPage] = useState(0);

  const versions = useQuery({ queryKey: ['versions', module], queryFn: () => api<ModuleVersion[]>(`/api/modules/${module}/versions`), enabled: !!module });
  const latest = versions.data?.[0]?.version_no ?? null;
  const cases = useQuery({
    queryKey: ['version-cases', module, latest],
    queryFn: () => api<VersionCase[]>(module ? `/api/modules/${module}/versions/${latest}/cases` : '/api/modules/cases'),
    enabled: !module || latest !== null,
  });
  const counts = useQuery({ queryKey: ['tag-counts', module ?? undefined, null], queryFn: () => api<TagCounts>(module ? `/api/modules/${module}/tag-counts` : '/api/modules/tag-counts') });
  const all = useQuery({ queryKey: ['tag-counts', undefined, null], queryFn: () => api<TagCounts>('/api/modules/tag-counts') });
  const mappers = counts.data?.tags.filter((t) => t.category === 'MAPPER') ?? [];
  const acrossModules = (code: string) => !module ? undefined : all.data?.tags.find((t) => t.category === 'MAPPER' && t.code === code)?.events;

  const filtered = useMemo(() => {
    const text = search.trim().toLowerCase();
    return (cases.data ?? []).filter((c) => {
      const ownTags = (c.tags ?? '').split(',');
      if (mapper === '__none__' ? ownTags.some((t) => t.startsWith('mapper:')) : mapper && !ownTags.includes(`mapper:${mapper}`)) return false;
      if (!text) return true;
      return [c.name, c.scenario_key, c.event_type, c.carrier, c.plan_codes ?? '', c.tags ?? ''].some((v) => v.toLowerCase().includes(text));
    });
  }, [cases.data, search, mapper]);
  const eventsOf = (c: { plan_codes: string | null }) => (c.plan_codes ? c.plan_codes.split(',').length : 1);
  const filteredEvents = filtered.reduce((n, c) => n + eventsOf(c), 0);
  useEffect(() => setPage(0), [search, mapper, module]);
  const shown = filtered.slice(page * PAGE, (page + 1) * PAGE);

  return (
    <Card
      title={`Test cases${cases.data ? ` (${filtered.length}${filtered.length !== cases.data.length ? ` of ${cases.data.length}` : ''}) · ${filteredEvents} event${filteredEvents === 1 ? '' : 's'}` : ''}`}
      actions={
        <>
          {module && <button className="btn" onClick={() => setModuleHistory(true)} disabled={!versions.data?.length}>Version history</button>}
          <button className="btn" onClick={() => setRunning(true)} disabled={!cases.data?.length}>▶ Run</button>
          {module && <Link className="btn primary" to={`/modules/${module}/cases/new`}>+ New test case</Link>}
        </>
      }
    >
      <div className="filters">
        <input type="search" aria-label="Search test cases" placeholder="Search name, event, plan code, tag" value={search} onChange={(e) => setSearch(e.target.value)} className="search" />
        <select aria-label="Mapper" value={mapper} onChange={(e) => setMapper(e.target.value)}>
          <option value="">All mappers ({counts.data?.total_events ?? 0} events)</option>
          {mappers.map((m) => {
            const total = acrossModules(m.code);
            return <option key={m.code} value={m.code}>{m.label} ({m.events}{total !== undefined && total !== m.events ? ` here · ${total} in all modules` : ''})</option>;
          })}
          {!!counts.data?.no_mapper_cases && <option value="__none__">⚠ No mapper ({counts.data.no_mapper_events})</option>}
        </select>
      </div>
      {!cases.data ? (
        <Empty>{versions.data && versions.data.length === 0 ? 'No test cases yet. Create the first one.' : 'Loading…'}</Empty>
      ) : shown.length === 0 ? (
        <Empty>No test cases match.</Empty>
      ) : (
        <table className="table">
          <thead><tr><th>Test case</th>{!module && <th>Module</th>}<th>Event</th><th>Topic</th><th>Tags</th><th>Rev</th><th /></tr></thead>
          <tbody>
            {shown.map((c) => (
              <tr key={`${c.module ?? module}/${c.scenario_key}`}>
                <td>
                  <Link to={`/modules/${c.module ?? module}/cases/${c.scenario_key}`} className="strong">{c.name}</Link>
                  <div className="mono muted small">{c.scenario_key}</div>
                </td>
                {!module && <td><span className="chip">{c.module}</span></td>}
                <td>
                  {c.carrier}
                  <div className="muted small">{c.event_type}</div>
                  {c.plan_codes && <div title={`${eventsOf(c)} event${eventsOf(c) === 1 ? '' : 's'}: one per plan code`}>{c.plan_codes.split(',').map((p) => <span className="chip plan-chip" key={p}>{p}</span>)}</div>}
                </td>
                <td><span className="chip" title={c.kafka_topic_pattern}>{c.kafka_kind}</span></td>
                <td>{c.tags?.split(',').filter((t) => !t.startsWith('event:') && !t.startsWith('path:')).map((t) => <span className="chip" key={t}>{t}</span>)}</td>
                <td>{c.revision}{!c.enabled && <span className="chip">disabled</span>}</td>
                <td className="actions">
                  <button className="link-btn" onClick={() => setHistory({ module: (c.module ?? module)!, key: c.scenario_key })}>History</button>{' '}
                  <Link to={`/modules/${c.module ?? module}/cases/${c.scenario_key}`}>Edit</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {filtered.length > PAGE && (
        <div className="pager">
          <button className="btn" disabled={page === 0} onClick={() => setPage(page - 1)}>← Previous</button>
          <span className="muted">{page * PAGE + 1}–{Math.min(filtered.length, (page + 1) * PAGE)} of {filtered.length}</span>
          <button className="btn" disabled={(page + 1) * PAGE >= filtered.length} onClick={() => setPage(page + 1)}>Next →</button>
        </div>
      )}
      {running && <RunDialog module={module ?? undefined} onClose={() => setRunning(false)} />}
      {history && <CaseHistory module={history.module} scenarioKey={history.key} onClose={() => setHistory(null)} />}
      {moduleHistory && module && <ModuleHistory module={module} onClose={() => setModuleHistory(false)} />}
    </Card>
  );
}
