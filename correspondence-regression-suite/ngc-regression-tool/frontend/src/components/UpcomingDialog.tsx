import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../lib/api';
import { formatDateTime, timeAgo } from '../lib/format';
import { runScope } from '../lib/runs';
import type { UpcomingRun } from '../lib/types';
import { Empty, ErrorNote, Modal } from './ui';

/** The next scheduled runs with the exact time they start and the test cases each one will play. */
export function UpcomingDialog({ onClose }: { onClose: () => void }) {
  const { data, error, isLoading } = useQuery({
    queryKey: ['upcoming-runs'],
    queryFn: () => api<UpcomingRun[]>('/api/schedules/upcoming?limit=10'),
    refetchInterval: 30000,
  });
  const [open, setOpen] = useState<Set<number>>(new Set());
  const toggle = (id: number) => setOpen((cur) => { const n = new Set(cur); if (!n.delete(id)) n.add(id); return n; });

  return (
    <Modal title="Upcoming scheduled runs" onClose={onClose} wide>
      <ErrorNote error={error} />
      {isLoading && <Empty>Loading…</Empty>}
      {data && data.length === 0 && <Empty>No enabled schedules. <Link to="/schedules" onClick={onClose}>Create one</Link>.</Empty>}
      <div className="upcoming-list">
        {data?.map((u) => {
          const isOpen = open.has(u.id);
          return (
            <div key={u.id} className="upcoming-item">
              <div className="upcoming-head">
                <div>
                  <strong>{formatDateTime(u.next_fire_at)}</strong>{' '}
                  <span className="muted">({u.executing ? 'executing now' : timeAgo(u.next_fire_at)})</span>
                </div>
                <div>
                  <Link to="/schedules" onClick={onClose} className="strong">{u.name}</Link> <span className="chip">{u.env}</span>
                  <span className="muted small"> {runScope(u)} · cron {u.cron_expression} ({u.timezone})</span>
                </div>
              </div>
              {u.problem ? (
                <div className="note error">This run would not start: {u.problem}</div>
              ) : (
                <>
                  <button className="link-btn" aria-expanded={isOpen} onClick={() => toggle(u.id)}>
                    {isOpen ? '▾' : '▸'} {u.scenarios.length} {u.scenarios.length === 1 ? 'scenario' : 'scenarios'} · {u.events} {u.events === 1 ? 'event' : 'events'}
                  </button>
                  {isOpen && (
                    <ul className="plain upcoming-scenarios">
                      {u.scenarios.map((s) => (
                        <li key={s.scenario_key}>
                          <strong>{s.scenario_name}</strong> <span className="chip">{s.carrier}</span>
                          <span className="muted small"> {s.event_type}</span>
                          {s.plan_codes.length > 0 && (
                            <div>{s.plan_codes.map((p) => <span key={p} className="chip plan-chip">{p}</span>)}</div>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              )}
            </div>
          );
        })}
      </div>
      <p className="muted small">Schedules play the newest version of their test cases, so this list reflects what exists now; edits made before the run time change it.</p>
    </Modal>
  );
}
