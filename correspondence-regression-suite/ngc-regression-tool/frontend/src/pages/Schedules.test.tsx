import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider } from '../lib/toast';
import { Schedules } from './Schedules';

function mockApi(calls: { url: string; method: string }[]) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    calls.push({ url, method });
    if (url === '/api/schedules' && method === 'POST') return new Response(JSON.stringify({ id: 7 }), { status: 201 });
    if (url === '/api/schedules/7/run') return new Response(JSON.stringify({ runKey: 'abc' }), { status: 202 });
    if (url === '/api/reference/environments') return new Response(JSON.stringify([{ code: 'dev', name: 'Dev' }]), { status: 200 });
    if (url === '/api/modules/tag-counts') return new Response(JSON.stringify({ tags: [], no_mapper_cases: 0, no_mapper_events: 0 }), { status: 200 });
    return new Response('[]', { status: 200 });
  }));
}

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter><ToastProvider><Schedules /></ToastProvider></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('Schedules: run now on create', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('only starts a run after saving when Run now is ticked', async () => {
    const calls: { url: string; method: string }[] = [];
    mockApi(calls);
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: '+ New schedule' }));
    await userEvent.type(screen.getByPlaceholderText('nightly-anniversary-dev'), 'nightly');

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls.some((c) => c.url === '/api/schedules' && c.method === 'POST')).toBe(true));
    expect(calls.some((c) => c.url.endsWith('/run'))).toBe(false);
  });

  it('saves then starts the first run immediately when Run now is ticked', async () => {
    const calls: { url: string; method: string }[] = [];
    mockApi(calls);
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: '+ New schedule' }));
    await userEvent.type(screen.getByPlaceholderText('nightly-anniversary-dev'), 'nightly');
    await userEvent.click(screen.getByRole('checkbox', { name: /Run now/ }));

    await userEvent.click(screen.getByRole('button', { name: 'Save and run now' }));

    await waitFor(() => expect(calls.some((c) => c.url === '/api/schedules/7/run' && c.method === 'POST')).toBe(true));
  });

  it('closes the form straight away and offers a link to the run once it has started', async () => {
    const calls: { url: string; method: string }[] = [];
    mockApi(calls);
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: '+ New schedule' }));
    await userEvent.type(screen.getByPlaceholderText('nightly-anniversary-dev'), 'nightly');
    await userEvent.click(screen.getByRole('checkbox', { name: /Run now/ }));

    await userEvent.click(screen.getByRole('button', { name: 'Save and run now' }));

    expect(await screen.findByRole('link', { name: 'View run' })).toHaveAttribute('href', '/runs/abc');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
