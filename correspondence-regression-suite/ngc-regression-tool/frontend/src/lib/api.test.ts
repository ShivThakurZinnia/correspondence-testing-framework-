import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, AUTH_EXPIRED, clearCredentials, getAuthHeader, json, setCredentials } from './api';

const respond = (status: number, body?: unknown) => vi.fn().mockResolvedValue(new Response(body === undefined ? null : JSON.stringify(body), { status }));

describe('api client', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  it('sends the stored Basic credentials and a JSON content type for bodies', async () => {
    const fetchMock = respond(200, { ok: true });
    vi.stubGlobal('fetch', fetchMock);
    setCredentials('admin', 'pw');

    await api('/api/x', { method: 'POST', ...json({ a: 1 }) });

    const [, init] = fetchMock.mock.calls[0];
    expect((init.headers as Headers).get('Authorization')).toBe('Basic ' + btoa('admin:pw'));
    expect((init.headers as Headers).get('Content-Type')).toBe('application/json');
    expect(init.body).toBe('{"a":1}');
  });

  it('raises the server validation errors', async () => {
    vi.stubGlobal('fetch', respond(400, { valid: false, errors: ['name is required', 'cron is bad'] }));

    await expect(api('/api/x')).rejects.toMatchObject({ status: 400, errors: ['name is required', 'cron is bad'], message: 'name is required; cron is bad' });
  });

  it('turns a 409 conflict into an ApiError with the reason', async () => {
    vi.stubGlobal('fetch', respond(409, { errors: ['Schedule is executing right now'] }));

    await expect(api('/api/x')).rejects.toBeInstanceOf(ApiError);
  });

  it('on 401 clears the login and tells the app to show the sign-in page', async () => {
    vi.stubGlobal('fetch', respond(401));
    setCredentials('a', 'b');
    const expired = vi.fn();
    window.addEventListener(AUTH_EXPIRED, expired);

    await expect(api('/api/x')).rejects.toMatchObject({ status: 401 });

    expect(getAuthHeader()).toBeNull();
    expect(expired).toHaveBeenCalledOnce();
    window.removeEventListener(AUTH_EXPIRED, expired);
  });

  it('returns undefined for empty 204 responses', async () => {
    vi.stubGlobal('fetch', respond(204));
    await expect(api('/api/x', { method: 'DELETE' })).resolves.toBeUndefined();
    clearCredentials();
  });
});
