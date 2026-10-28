import type { ReactNode } from 'react';
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './lib/auth';
import { ToastProvider } from './lib/toast';
import { CaseEditor } from './pages/CaseEditor';
import { Health } from './pages/Health';
import { EventDetail } from './pages/EventDetail';
import { Events } from './pages/Events';
import { Login } from './pages/Login';
import { Modules } from './pages/Modules';
import { Overview } from './pages/Overview';
import { RunDetail } from './pages/RunDetail';
import { Runs } from './pages/Runs';
import { Schedules } from './pages/Schedules';

const icon = (d: ReactNode) => (
  <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">{d}</svg>
);

const NAV = [
  { to: '/', label: 'Overview', end: true, icon: icon(<><path d="M3 3h6v6H3zM11 3h6v4h-6zM11 9h6v8h-6zM3 11h6v6H3z"/></>) },
  { to: '/runs', label: 'Runs', icon: icon(<><path d="M5 3.5v13l11-6.5z"/></>) },
  { to: '/events', label: 'Events', icon: icon(<><circle cx="9" cy="9" r="5.5"/><path d="M13 13l4 4"/></>) },
  { to: '/health', label: 'Health', icon: icon(<><path d="M2 10h4l2-5 3 10 2-5h5"/></>) },
  { to: '/modules', label: 'Test modules', icon: icon(<><path d="M3 5h14M3 10h14M3 15h14"/></>) },
  { to: '/schedules', label: 'Schedules', icon: icon(<><circle cx="10" cy="10" r="7"/><path d="M10 5.5V10l3 2"/></>) },
];

export function App() {
  const { authenticated, logout } = useAuth();
  if (!authenticated) return <Login />;
  return (
    <ToastProvider>
    <div className="shell">
      <aside className="sidebar">
        <div className="brand"><span className="logo" aria-hidden /><span>NGC Regression<small>Regression suite</small></span></div>
        <nav>
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.end} className={({ isActive }) => (isActive ? 'nav on' : 'nav')}>
              <span className="nav-icon" aria-hidden>{n.icon}</span>{n.label}
            </NavLink>
          ))}
        </nav>
        <button className="nav signout" onClick={logout}>Sign out</button>
      </aside>
      <main className="content">
        <Routes>
          <Route path="/" element={<Overview />} />
          <Route path="/runs" element={<Runs />} />
          <Route path="/runs/:runKey" element={<RunDetail />} />
          <Route path="/health" element={<Health />} />
          <Route path="/events" element={<Events />} />
          <Route path="/events/:correlationId" element={<EventDetail />} />
          <Route path="/modules" element={<Modules />} />
          <Route path="/modules/:module" element={<Modules />} />
          <Route path="/modules/:module/cases/:key" element={<CaseEditor />} />
          <Route path="/schedules" element={<Schedules />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
    </ToastProvider>
  );
}
