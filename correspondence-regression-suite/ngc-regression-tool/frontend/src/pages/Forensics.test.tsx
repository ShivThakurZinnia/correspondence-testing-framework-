import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ForensicsEvent, ForensicsSearch, ForensicsTree } from '../lib/types';
import { Forensics } from './Forensics';

const tree: ForensicsTree = {
  window_hours: 24,
  carriers: [
    {
      carrier: 'GILI', total: 40, failed: 12, rejected_dead_letters: 1, rejected_invalid: 3,
      event_types: [
        { event_type: 'policy.rmdtriggerpostapv.completed', total: 17, failed: 10, technical: 8, business: 2, stuck: 0, ok: 7 },
        { event_type: 'policy.activation.completed', total: 23, failed: 2, technical: 2, business: 0, stuck: 0, ok: 21 },
      ],
    },
    { carrier: 'ELIC', total: 5, failed: 0, rejected_dead_letters: 0, rejected_invalid: 0, event_types: [{ event_type: 'policy.activation.completed', total: 5, failed: 0, technical: 0, business: 0, stuck: 0, ok: 5 }] },
  ],
};

const event = (n: number): ForensicsEvent => ({
  unique_id: `u${n}`, correlation_id: `c${n}`, carrier: 'GILI', event_type: 'policy.rmdtriggerpostapv.completed', policy_number: `AIUL${n}`,
  plan_code: 'GCFIA903', source: 'zahara', status: 'FAILED', error_category: 'TECHNICAL_FAILURE', error: 'Zahara 404 for [email]',
  retry_in_progress: false, created_at: '2026-10-28T10:00:00Z', updated_at: null, targets: [{ role: 'OWNER', status: 'TECHNICAL_FAILURE', delivery_type: 'PRINT', error: null }],
});

function mockApi(urls: string[], pages: ForensicsSearch[], treeResponse: Response | null = null) {
  let page = 0;
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    urls.push(url);
    if (url.startsWith('/api/forensics/tree')) return treeResponse ?? new Response(JSON.stringify(tree), { status: 200 });
    if (url.startsWith('/api/forensics/events')) return new Response(JSON.stringify(pages[Math.min(page++, pages.length - 1)]), { status: 200 });
    return new Response('{}', { status: 200 });
  }));
}

const renderPage = (entry = '/forensics') =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[entry]}><Forensics /></MemoryRouter>
    </QueryClientProvider>,
  );

describe('Forensics browse', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('asks the user to pick a carrier first and lets them narrow the carrier list by typing', async () => {
    mockApi([], []);
    renderPage();

    expect(await screen.findByText(/Pick a carrier on the left/)).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /GILI/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /ELIC/ })).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Find a carrier'), 'gi');

    expect(screen.getByRole('button', { name: /GILI/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /ELIC/ })).not.toBeInTheDocument();
  });

  it('drills from carrier to event type to outcome and then lists the matching events', async () => {
    const urls: string[] = [];
    mockApi(urls, [{ events: [event(1)], rejected: [], has_more: false, next_before: null }]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /GILI/ }));
    expect(screen.getByText(/Rejected before processing: 1 dead letter, 3 invalid/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /policy\.rmdtriggerpostapv\.completed/ }));

    expect(screen.getByRole('button', { name: /Technical failure/ })).toHaveTextContent('8');
    expect(screen.getByRole('button', { name: /Business failure/ })).toHaveTextContent('2');
    expect(screen.getByRole('button', { name: /Sent \/ OK/ })).toHaveTextContent('7');
    expect(screen.queryByRole('button', { name: /Stuck/ })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /Technical failure/ }));

    const row = (await screen.findByText('AIUL1')).closest('tr')!;
    expect(within(row).getByText('Zahara 404 for [email]')).toBeInTheDocument();
    expect(within(row).getByText('FAILED')).toBeInTheDocument();
    expect(within(row).getByText(/OWNER TECHNICAL_FAILURE/)).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: 'History' })).toHaveAttribute('href', '/forensics/events/c1?uniqueId=u1');
    const search = urls.filter((u) => u.startsWith('/api/forensics/events')).pop()!;
    expect(new URLSearchParams(search.split('?')[1]).get('carrier')).toBe('GILI');
    expect(search).toContain('eventType=policy.rmdtriggerpostapv.completed');
    expect(search).toContain('outcome=technical');
    expect(search).toContain('hours=24');
  });

  it('shows events rejected before processing and pages with load more', async () => {
    const urls: string[] = [];
    mockApi(urls, [
      { events: [event(1)], rejected: [{ kind: 'DEAD_LETTER', correlation_id: 'cx', carrier: 'GILI', event_type: 'policy.x', policy_number: null, status: 'PENDING_REPLAY', reason: 'RuleExecutionException: 503', at: '2026-10-28T09:00:00Z' }], has_more: true, next_before: '2026-10-28T10:00:00Z' },
      { events: [event(2)], rejected: [], has_more: false, next_before: null },
    ]);
    renderPage('/forensics?carrier=GILI');

    expect(await screen.findByText('Rejected before processing (1)')).toBeInTheDocument();
    expect(screen.getByText(/RuleExecutionException: 503/)).toBeInTheDocument();
    expect(screen.getByText('Dead letter')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByText('AIUL2')).toBeInTheDocument();
    expect(screen.getByText('AIUL1')).toBeInTheDocument();
    expect(urls.filter((u) => u.startsWith('/api/forensics/events')).pop()).toContain('before=2026-10-28T10%3A00%3A00Z');
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('jumps straight to an event by policy number, with no time window', async () => {
    const urls: string[] = [];
    mockApi(urls, [{ events: [event(7)], rejected: [], has_more: false, next_before: null }]);
    renderPage();

    await userEvent.selectOptions(await screen.findByLabelText('Jump to'), 'policyNumber');
    await userEvent.type(screen.getByLabelText('Jump to value'), 'AIUL7');
    await userEvent.click(screen.getByRole('button', { name: 'Find' }));

    expect(await screen.findByText('AIUL7', { selector: 'td' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Selection' })).toHaveTextContent('Policy number: AIUL7 · any time');
    expect(urls.filter((u) => u.startsWith('/api/forensics/events')).pop()).toContain('policyNumber=AIUL7');
  });

  it('explains when Mongo is not configured instead of showing an empty tree', async () => {
    mockApi([], [], new Response(JSON.stringify({ errors: ['Mongo is not configured (MONGODB_URI, MONGODB_DATABASE)'] }), { status: 503 }));
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('Mongo is not configured');
    await waitFor(() => expect(screen.queryByLabelText('Find a carrier')).not.toBeInTheDocument());
  });
});
