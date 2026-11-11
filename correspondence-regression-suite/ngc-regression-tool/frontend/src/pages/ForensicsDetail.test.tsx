import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider } from '../lib/toast';
import type { ForensicsHistory } from '../lib/types';
import { ForensicsDetail } from './ForensicsDetail';

const attempt = (n: number, status: string) => ({
  unique_id: `u${n}`, correlation_id: 'c-1', carrier: 'GILI', event_type: 'policy.x', policy_number: 'AIUL300626252', plan_code: 'GCFIA903', source: 'zahara',
  status, error_category: status === 'FAILED' ? 'TECHNICAL_FAILURE' : null, error: status === 'FAILED' ? 'Zahara 404' : null, retry_in_progress: false,
  created_at: `2026-10-28T1${n}:00:00Z`, updated_at: null, targets: [{ role: 'OWNER', status: 'SENT_TO_CDS', delivery_type: 'PRINT', error: null }],
});

const history = (over: Partial<ForensicsHistory> = {}): ForensicsHistory => ({
  correlation_id: 'c-1', selected_attempt: 'u2', attempt_count: 2, attempts: [attempt(1, 'FAILED'), attempt(2, 'SENT_TO_CDS')],
  stages: [
    { code: 'RECEIVED', label: 'Received', state: 'done', at: '2026-10-28T11:00:00Z' },
    { code: 'SENT_TO_CDS', label: 'Sent to CDS', state: 'done', at: null },
    { code: 'PDF_STARTED', label: 'PDF started', state: 'pending', at: null },
  ],
  timeline: [
    { at: '2026-10-28T11:00:00Z', collection: 'incoming_events', type: 'policy.x', status: 'RECEIVED', role: null, detail: null, attempt: 'u2' },
    { at: '2026-10-28T11:00:05Z', collection: 'technical_failures', type: 'technical failure', status: 'TECHNICAL_FAILURE', role: null, detail: 'worker: CarrierDetailsWorker: boom', attempt: 'u2' },
  ],
  retries: [{ worker: 'CarrierDetailsWorker', status: 'RETRY_ATTEMPT_3', attempts: 3, last_attempt_at: '2026-10-28T12:00:00Z', next_eligible_at: null, attempt: 'u2' }],
  markers: ['Replayed: 2 processing attempts share this correlationId.', 'Execution steps are from the last attempt only; earlier attempts were overwritten.'],
  ...over,
});

function mockApi(urls: string[], body: ForensicsHistory | { status: number; errors: string[] }) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    urls.push(url);
    return 'status' in body ? new Response(JSON.stringify({ errors: body.errors }), { status: body.status }) : new Response(JSON.stringify(body), { status: 200 });
  }));
}

const renderPage = (entry = '/forensics/events/c-1?uniqueId=u2') =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[entry]}>
        <ToastProvider>
          <Routes><Route path="/forensics/events/:correlationId" element={<ForensicsDetail />} /></Routes>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );

describe('Forensics event history', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows stages, the limits of the data, the attempt, retries and the merged timeline', async () => {
    const urls: string[] = [];
    mockApi(urls, history());
    renderPage();

    const stages = await screen.findByRole('list', { name: 'Stages' });
    expect(within(stages).getAllByRole('listitem').map((s) => s.className)).toEqual(['step done', 'step done', 'step ']);
    expect(urls[0]).toBe('/api/forensics/events/c-1?uniqueId=u2');
    expect(screen.getByText('What this history cannot tell you')).toBeInTheDocument();
    expect(screen.getByText(/Execution steps are from the last attempt only/)).toBeInTheDocument();
    expect(screen.getByText('This attempt')).toBeInTheDocument();
    expect(screen.getByText('AIUL300626252')).toBeInTheDocument();
    expect(screen.getByText('Automatic retries')).toBeInTheDocument();
    expect(screen.getByText('worker: CarrierDetailsWorker: boom')).toBeInTheDocument();
    expect(screen.getByText('Timeline (2)')).toBeInTheDocument();
  });

  it('lets the user switch attempt or look at all of them', async () => {
    const urls: string[] = [];
    mockApi(urls, history());
    renderPage();

    const select = await screen.findByLabelText('Attempt');
    expect(within(select).getAllByRole('option')).toHaveLength(3);
    await userEvent.selectOptions(select, 'all');

    expect(urls[urls.length - 1]).toBe('/api/forensics/events/c-1?uniqueId=all');
  });

  it('copies a fact sheet to the clipboard', async () => {
    mockApi([], history());
    const writeText = vi.fn(async () => undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Copy fact sheet' }));

    expect(writeText).toHaveBeenCalledTimes(1);
    expect(String(writeText.mock.calls[0][0 as never])).toContain('Event history for correlationId c-1');
    expect(await screen.findByText('Fact sheet copied.')).toBeInTheDocument();
  });

  it('says so when the event was rejected before processing', async () => {
    mockApi([], history({ attempts: [], attempt_count: 0, selected_attempt: null, markers: ['Never persisted: captured as a dead letter before an incoming event was created.'] }));
    renderPage('/forensics/events/c-1');

    expect(await screen.findByText(/rejected before processing/)).toBeInTheDocument();
    expect(screen.getByText(/Never persisted/)).toBeInTheDocument();
  });

  it('shows a readable message when NGC holds nothing for the id', async () => {
    mockApi([], { status: 404, errors: ['NGC holds nothing for this correlationId'] });
    renderPage('/forensics/events/c-1');

    expect(await screen.findByRole('alert')).toHaveTextContent('NGC holds nothing for this correlationId');
  });
});
