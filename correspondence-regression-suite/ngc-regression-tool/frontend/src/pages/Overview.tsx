import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { RunDialog } from '../components/RunDialog';
import { RunsTable } from '../components/RunsTable';
import { Card, Empty, ErrorNote, PageHeader, StatusIcon } from '../components/ui';
import { api } from '../lib/api';
import { formatDateTime, percent, shortKey, statusTone, timeAgo } from '../lib/format';
import type { Dashboard } from '../lib/types';

export function Overview() {
  const [running, setRunning] = useState(false);
  const { data, error } = useQuery({
    queryKey: ['dashboard'],
    queryFn: () => api<Dashboard>('/api/dashboard'),
    refetchInterval: (q) => (q.state.data?.running.length ? 2000 : 10000),
  });

  return (
    <>
      <PageHeader
        title="Overview"
        subtitle="Latest result per environment and selection, what is running now, and what runs next."
        actions={
          <button className="btn primary" onClick={() => setRunning(true)}>
            ▶ Run tests
          </button>
        }
      />
      <ErrorNote error={error} />

      <div className="tiles">
        {data?.tiles.map((t) => {
          const rate = percent(t.passed_7d, t.runs_7d);
          const tone = t.active_runs > 0 ? 'run' : statusTone(t.last_status);
          return (
            <article key={`${t.env}/${t.scope ?? ''}`} className={`tile tone-${tone}`}>
              <header>
                <StatusIcon status={t.active_runs > 0 ? 'RUNNING' : t.last_status} size={22} />
                <div>
                  <strong>{t.scope ? t.scope.split(',').join(' · ') : 'All tests'}</strong>
                  <span className="chip">{t.env}</span>
                </div>
              </header>
              <div className="tile-meta">
                {t.last_run_key ? (
                  <Link to={`/runs/${t.last_run_key}`} className="mono">
                    Last run #{shortKey(t.last_run_key)}
                  </Link>
                ) : (
                  <span className="muted">No runs yet</span>
                )}
                {t.active_runs > 0 && <span className="run-text">{t.active_runs} running</span>}
              </div>
              <div className="bar" title={`${t.passed_7d} of ${t.runs_7d} runs passed in 7 days`}>
                <span style={{ width: `${t.runs_7d ? rate : 0}%` }} />
              </div>
              <footer className="muted">{t.runs_7d ? `${rate}% passed · ${t.runs_7d} runs / 7 days` : 'No runs in 7 days'}</footer>
            </article>
          );
        })}
      </div>

      <div className="two-col">
        <Card title="Running now">
          {data?.running.length ? <RunsTable runs={data.running} /> : <Empty>Nothing is running.</Empty>}
        </Card>
        <Card title="Upcoming schedules" actions={<Link to="/schedules">Manage</Link>}>
          {data?.upcoming.length ? (
            <ul className="plain">
              {data.upcoming.map((u) => (
                <li key={u.id} className="sched-row">
                  <div>
                    <strong>{u.name}</strong>
                    <div className="muted">
                      {u.env} · {u.tag_expression ? u.tag_expression.split(',').join(' · ') : 'all tests'}
                    </div>
                  </div>
                  <div className="right" title={formatDateTime(u.next_fire_at)}>
                    {u.executing ? <span className="run-text">executing</span> : timeAgo(u.next_fire_at)}
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <Empty>No schedules yet.</Empty>
          )}
        </Card>
      </div>

      <Card title="Recent runs" actions={<Link to="/runs">All runs</Link>}>
        <RunsTable runs={data?.recent ?? []} />
      </Card>
      {running && <RunDialog onClose={() => setRunning(false)} />}
    </>
  );
}
