import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Card, Empty, ErrorNote, PageHeader, StatusIcon } from '../components/ui';
import { api } from '../lib/api';
import { timeAgo } from '../lib/format';
import type { CaseRow, Tag } from '../lib/types';

export function Events() {
  const [f, setF] = useState({ correlationId: '', scenario: '', status: '', env: '', tag: '' });
  const [debounced, setDebounced] = useState(f);
  const tags = useQuery({ queryKey: ['tags'], queryFn: () => api<Tag[]>('/api/reference/tags') });
  const query = new URLSearchParams({ size: '100' });
  Object.entries(debounced).forEach(([k, v]) => v && query.set(k, v));
  const events = useQuery({ queryKey: ['events', debounced], queryFn: () => api<CaseRow[]>(`/api/events?${query}`) });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });

  return (
    <>
      <PageHeader title="Events" subtitle="Every event ever played, searchable by correlation id, scenario, result or tag." />
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setDebounced(f); }}>
        <input placeholder="Correlation id (any part)" value={f.correlationId} onChange={set('correlationId')} />
        <input placeholder="Scenario key" value={f.scenario} onChange={set('scenario')} />
        <select aria-label="Status" value={f.status} onChange={set('status')}>
          <option value="">Any result</option>
          {['PASSED', 'FAILED', 'TIMEOUT', 'ERROR', 'RUNNING'].map((s) => <option key={s}>{s}</option>)}
        </select>
        <select aria-label="Tag" value={f.tag} onChange={set('tag')}>
          <option value="">Any tag</option>
          {tags.data?.map((t) => <option key={`${t.category}:${t.code}`} value={`${t.category.toLowerCase()}:${t.code}`}>{t.category.toLowerCase()}:{t.code}</option>)}
        </select>
        <button className="btn primary">Search</button>
      </form>
      <ErrorNote error={events.error} />
      <Card>
        {events.data?.length ? (
          <table className="table">
            <thead><tr><th style={{ width: 28 }} /><th>Scenario</th><th>Correlation id</th><th>Env</th><th>NGC status</th><th>Run</th><th>When</th></tr></thead>
            <tbody>
              {events.data.map((e) => (
                <tr key={e.correlation_id}>
                  <td><StatusIcon status={e.status} /></td>
                  <td><Link to={`/events/${e.correlation_id}`} className="strong">{e.scenario_name}</Link>{e.plan_code && <span className="chip plan-chip">{e.plan_code}</span>}</td>
                  <td className="mono small">{e.correlation_id}</td>
                  <td><span className="chip">{e.env}</span></td>
                  <td>{e.final_incoming_status ?? '–'}</td>
                  <td><Link to={`/runs/${e.run_key}`} className="mono">#{e.run_key.slice(0, 8)}</Link></td>
                  <td>{timeAgo(e.started_at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <Empty>No events match.</Empty>
        )}
      </Card>
    </>
  );
}
