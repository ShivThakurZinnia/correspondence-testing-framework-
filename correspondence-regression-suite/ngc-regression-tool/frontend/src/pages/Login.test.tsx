import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from '../lib/auth';
import { getAuthHeader } from '../lib/api';
import { Login } from './Login';

function Probe() {
  const { authenticated } = useAuth();
  return authenticated ? <p>signed in</p> : <Login />;
}

describe('Login', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  it('shows an error and keeps no credentials when the password is wrong', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    const user = userEvent.setup();
    render(<AuthProvider><Probe /></AuthProvider>);

    await user.type(screen.getByLabelText('Password'), 'nope');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong user or password');
    expect(getAuthHeader()).toBeNull();
  });

  it('signs in with the right password and remembers it for the session', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('[]', { status: 200 })));
    const user = userEvent.setup();
    render(<AuthProvider><Probe /></AuthProvider>);

    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled();
    await user.type(screen.getByLabelText('Password'), 'pw123');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(screen.getByText('signed in')).toBeInTheDocument());
    expect(getAuthHeader()).toBe('Basic ' + btoa('admin:pw123'));
  });
});
