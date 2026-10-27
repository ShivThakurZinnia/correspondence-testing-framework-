import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, AUTH_EXPIRED, clearCredentials, getAuthHeader, setCredentials } from './api';

interface AuthState {
  authenticated: boolean;
  login: (user: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [authenticated, setAuthenticated] = useState(() => getAuthHeader() !== null);

  useEffect(() => {
    const expired = () => setAuthenticated(false);
    window.addEventListener(AUTH_EXPIRED, expired);
    return () => window.removeEventListener(AUTH_EXPIRED, expired);
  }, []);

  const login = useCallback(async (user: string, password: string) => {
    setCredentials(user, password);
    try {
      await api('/api/reference/environments');
      setAuthenticated(true);
    } catch (e) {
      clearCredentials();
      throw e;
    }
  }, []);

  const logout = useCallback(() => {
    clearCredentials();
    setAuthenticated(false);
  }, []);

  return <AuthContext.Provider value={{ authenticated, login, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
