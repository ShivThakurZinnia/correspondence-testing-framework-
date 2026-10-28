const STORAGE_KEY = 'ngc-regression-auth';
export const AUTH_EXPIRED = 'ngc-auth-expired';

export class ApiError extends Error {
  constructor(
    public status: number,
    public errors: string[],
  ) {
    super(errors.join('; ') || `Request failed (${status})`);
  }
}

export function getAuthHeader(): string | null {
  return sessionStorage.getItem(STORAGE_KEY);
}

export function setCredentials(user: string, password: string): void {
  sessionStorage.setItem(STORAGE_KEY, 'Basic ' + btoa(`${user}:${password}`));
}

export function clearCredentials(): void {
  sessionStorage.removeItem(STORAGE_KEY);
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const auth = getAuthHeader();
  if (auth) headers.set('Authorization', auth);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');

  const res = await fetch(path, { ...init, headers });
  if (res.status === 401) {
    clearCredentials();
    window.dispatchEvent(new Event(AUTH_EXPIRED));
    throw new ApiError(401, ['Login required']);
  }
  const text = await res.text();
  const body = text ? safeJson(text) : undefined;
  if (!res.ok) {
    const errors = body && Array.isArray(body.errors) ? (body.errors as string[]) : [];
    throw new ApiError(res.status, errors.length ? errors : [`Request failed (${res.status})`]);
  }
  return body as T;
}

function safeJson(text: string): any {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export const json = (value: unknown): RequestInit => ({ body: JSON.stringify(value) });
