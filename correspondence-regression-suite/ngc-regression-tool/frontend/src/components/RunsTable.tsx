import { runScope } from '../lib/runs';
import { Link } from 'react-router-dom';
import { formatDuration, shortKey, timeAgo } from '../lib/format';
import type { RunRow } from '../lib/types';
import { Empty, StatusIcon } from './ui';

export function RunsTable({ runs }: { runs: RunRow[] }) {
  if (runs.length === 0) return <Empty>No runs yet.</Empty>;
  return (
    <table className="table runs">
      <thead>
        <tr>
          <th style={{ width: 28 }} />
          <th>Run</th>
          <th>Scope</th>
          <th>Env</th>
          <th>Result</th>
          <th>Trigger</th>
          <th>Duration</th>
          <th>Started</th>
        </tr>
      </thead>
      <tbody>
        {runs.map((r) => (
          <tr key={r.run_key} className={`row-${r.status.toLowerCase()}`}>
            <td>
              <StatusIcon status={r.status} />
            </td>
            <td>
              <Link to={`/runs/${r.run_key}`} className="mono strong">
                #{shortKey(r.run_key)}
              </Link>
            </td>
            <td>
              {runScope(r)}
            </td>
            <td>
              <span className="chip">{r.env}</span>
            </td>
            <td>
              {['RUNNING', 'QUEUED'].includes(r.status) ? (
                <span className="run-text">{r.passed_cases + r.failed_cases}/{r.total_cases} done</span>
              ) : (
                <span className={r.failed_cases > 0 ? 'bad-text' : ''}>
                  {r.passed_cases}/{r.total_cases} passed
                </span>
              )}
              {r.failed_cases > 0 && <span className="bad-text"> · {r.failed_cases} failed</span>}
            </td>
            <td>
              {r.trigger_source.toLowerCase()}
              <span className="muted"> {r.schedule_name ?? r.triggered_by ?? ''}</span>
            </td>
            <td>{formatDuration(r.duration_seconds)}</td>
            <td title={r.queued_at}>{timeAgo(r.started_at ?? r.queued_at)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
