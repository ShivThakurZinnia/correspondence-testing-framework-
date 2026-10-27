import { useState, type FormEvent } from 'react';
import { useAuth } from '../lib/auth';
import { ErrorNote } from '../components/ui';

export function Login() {
  const { login } = useAuth();
  const [user, setUser] = useState('admin');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(user, password);
    } catch {
      setError(new Error('Wrong user or password'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <div className="brand big">
          <span className="logo">✓</span> NGC Regression
        </div>
        <p className="muted">Sign in to see runs, schedules and test modules.</p>
        <label className="field">
          <span className="field-label">User</span>
          <input value={user} onChange={(e) => setUser(e.target.value)} autoComplete="username" autoFocus />
        </label>
        <label className="field">
          <span className="field-label">Password</span>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        </label>
        <ErrorNote error={error} />
        <button className="btn primary block" disabled={busy || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
