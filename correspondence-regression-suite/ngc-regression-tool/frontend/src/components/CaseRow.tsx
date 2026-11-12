import { Link } from 'react-router-dom';
import type { CaseRow as Case } from '../lib/types';
import { StageStepper, StatusIcon } from './ui';

/** One event of a run. `inGroup` drops the scenario name, which the group header already shows. */
export function CaseRow({ c, inGroup = false }: { c: Case; inGroup?: boolean }) {
  return (
    <Link to={`/events/${c.correlation_id}`} className={`case-row case-${c.status.toLowerCase()}${inGroup ? ' in-group' : ''}`}>
      <StatusIcon status={c.status} size={22} />
      <div className="case-main">
        <div className="case-title">
          {inGroup ? (
            <strong>{c.plan_code ? `Plan ${c.plan_code}` : 'Event'}</strong>
          ) : (
            <>
              <strong>{c.scenario_name}</strong>
              <span className="chip">{c.carrier}</span>
              {c.plan_code && <span className="chip plan-chip" title="Plan code">{c.plan_code}</span>}
              <span className="muted">{c.event_type}</span>
            </>
          )}
        </div>
        <div className="mono muted small">{c.correlation_id}</div>
        {c.failure_summary && <div className="bad-text small">{c.failure_summary}</div>}
      </div>
      <div className="case-steps">
        {c.progress && <StageStepper progress={c.progress} status={c.status} compact />}
        <span className="muted small">{c.final_incoming_status ?? (c.status === 'PENDING' ? 'waiting to be sent' : 'waiting for NGC')}</span>
      </div>
    </Link>
  );
}
