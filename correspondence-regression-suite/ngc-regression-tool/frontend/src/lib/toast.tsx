import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

export interface ToastInput {
  text: string;
  tone?: 'info' | 'ok' | 'bad';
  linkTo?: string;
  linkText?: string;
  /** How long it stays; 0 keeps it until dismissed. */
  ms?: number;
}

interface Toast extends ToastInput {
  id: number;
}

type Show = (t: ToastInput) => number;
type Dismiss = (id: number) => void;

const Ctx = createContext<{ show: Show; dismiss: Dismiss }>({ show: () => 0, dismiss: () => undefined });

export const useToast = () => useContext(Ctx);

/** Short-lived notices in the corner, so slow work can finish in the background without blocking a form. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const dismiss = useCallback<Dismiss>((id) => setToasts((all) => all.filter((t) => t.id !== id)), []);
  const show = useCallback<Show>((t) => {
    const id = Date.now() + Math.random();
    setToasts((all) => [...all, { ...t, id }]);
    const ms = t.ms ?? 10000;
    if (ms > 0) window.setTimeout(() => dismiss(id), ms);
    return id;
  }, [dismiss]);
  const value = useMemo(() => ({ show, dismiss }), [show, dismiss]);

  return (
    <Ctx.Provider value={value}>
      {children}
      <div className="toasts" role="region" aria-label="Notifications" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast toast-${t.tone ?? 'info'}`} role="status">
            <span>{t.text}</span>
            {t.linkTo && <Link to={t.linkTo} onClick={() => dismiss(t.id)} className="toast-link">{t.linkText ?? 'View'}</Link>}
            <button className="icon-btn" aria-label="Dismiss" onClick={() => dismiss(t.id)}>×</button>
          </div>
        ))}
      </div>
    </Ctx.Provider>
  );
}
