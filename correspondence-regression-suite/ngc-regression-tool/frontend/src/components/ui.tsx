import { useEffect, type ReactNode } from 'react';
import { statusTone, stageStates, type Tone } from '../lib/format';
import type { Stage } from '../lib/types';

export function StatusIcon({ status, size = 18 }: { status: string | null | undefined; size?: number }) {
  const tone: Tone = statusTone(status);
  const common = { width: size, height: size, viewBox: '0 0 20 20', 'aria-label': status ?? 'no result', role: 'img' } as const;
  if (tone === 'run') {
    return (
      <svg {...common} className="icon spin" fill="none">
        <circle cx="10" cy="10" r="7.5" stroke="currentColor" strokeOpacity=".25" strokeWidth="2.5" />
        <path d="M10 2.5a7.5 7.5 0 0 1 7.5 7.5" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
      </svg>
    );
  }
  return (
    <svg {...common} className={`icon tone-${tone}`}>
      <circle cx="10" cy="10" r="9" fill="currentColor" />
      {tone === 'ok' && <path d="M5.8 10.4l3 3 5.6-6.2" stroke="#fff" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" />}
      {tone === 'bad' && <path d="M6.8 6.8l6.4 6.4M13.2 6.8l-6.4 6.4" stroke="#fff" strokeWidth="2" strokeLinecap="round" />}
      {tone === 'warn' && <path d="M6 10h8" stroke="#fff" strokeWidth="2" strokeLinecap="round" />}
      {tone === 'idle' && <circle cx="10" cy="10" r="2.2" fill="#fff" />}
    </svg>
  );
}

export function StatusBadge({ status }: { status: string }) {
  return (
    <span className={`badge tone-${statusTone(status)}`}>
      <StatusIcon status={status} size={12} />
      {status.charAt(0) + status.slice(1).toLowerCase()}
    </span>
  );
}

export function StageStepper({ progress, status, compact = false }: { progress: Stage[]; status: string; compact?: boolean }) {
  const stages = stageStates(progress, status);
  const reached = stages.filter((s) => s.state === 'done').length;
  return (
    <div className={compact ? 'stepper compact' : 'stepper'} role="list" aria-label={`${reached} of ${stages.length} stages reached`}>
      {stages.map((s) => (
        <div key={s.code} role="listitem" className={`step ${s.state}`} title={`${s.label}: ${s.state}`}>
          <span className="dot" />
          {!compact && <span className="label">{s.label}</span>}
        </div>
      ))}
    </div>
  );
}

export function Card({ title, actions, children, className = '' }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <header className="card-head">
          <h2>{title}</h2>
          <div className="card-actions">{actions}</div>
        </header>
      )}
      {children}
    </section>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      <div className="page-actions">{actions}</div>
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof Error ? error.message : String(error);
  return (
    <div className="note error" role="alert">
      {message}
    </div>
  );
}

export function Modal({ title, onClose, children, wide = false }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className={wide ? 'modal wide' : 'modal'} role="dialog" aria-modal="true" aria-label={title} onMouseDown={(e) => e.stopPropagation()}>
        <header>
          <h2>{title}</h2>
          <button className="icon-btn" onClick={onClose} aria-label="Close">
            ×
          </button>
        </header>
        <div className="modal-body">{children}</div>
      </div>
    </div>
  );
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="hint">{hint}</span>}
    </label>
  );
}
