import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { HealthReport } from '../lib/types';
import { Health, minutes, pct } from './Health';

const report = (over: Partial<HealthReport> = {}): HealthReport => ({
  generated_at: new Date().toISOString(),
  window_hours: 168,
  level: 'bad',
  last_event_at: '2026-10-21T13:00:00Z',
  events_in_window: 40,
  issues: [
    { level: 'bad', text: '38 incoming event(s) have been PROCESSING for more than 30 minutes (oldest 5 days)' },
    { level: 'warn', text: '3 event(s) used up all automatic retries and need manual attention' },
    { level: 'info', text: 'No events arrived in the last 24 hours, so rates are not meaningful' },
  ],
  stuck: { level: 'bad', threshold_minutes: 30, incoming_stuck: 38, oldest_incoming_minutes: 8576, incoming_stale: 1247, internal_stuck: 0, timed_overdue_recent: 4, timed_overdue_older: 2337 },
  failures: {
    level: 'warn', total: 40, failed: 6, technical: 4, business: 2, failure_rate: 0.15,
    by_event: [
      { carrier: 'GILI', event_type: 'policy.rmdtriggerpostapv.completed', total: 17, failed: 10, technical: 8, business: 2, sent_to_cds: 7, processed: 0, failure_rate: 0.588 },
      { carrier: 'GILI', event_type: 'policy.activation.completed', total: 23, failed: 0, technical: 0, business: 0, sent_to_cds: 23, processed: 0, failure_rate: 0 },
    ],
  },
  workers: [{ name: 'SearchDocumentWorker', count: 167 }],
  retries: { level: 'warn', successful: 25, exhausted: 3, retrying: 6 },
  dead_letters: { level: 'bad', pending: 10, exhausted: 0, replayed: 4, oldest_pending_minutes: 110887 },
  funnel: { level: 'warn', sent_to_cds: 22, pdf_started: 3, pdf_completed: 4, delivery: 1, pdf_started_rate: 0.136, pdf_completed_rate: 0.182, median_seconds_to_pdf_started: 67240, p90_seconds_to_pdf_started: 70000 },
  new_event_types: [{ carrier: 'GILI', event_type: 'brand.new.event', first_seen: '2026-10-20T10:00:00Z' }],
  ...over,
});

function mockApi(r: HealthReport | { status: number; errors: string[] }) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.startsWith('/api/health/history')) return new Response('[]', { status: 200 });
    if ('status' in r) return new Response(JSON.stringify({ errors: r.errors }), { status: r.status });
    return new Response(JSON.stringify({ environment: 'dev', report: r }), { status: 200 });
  }));
}

const renderPage = () =>
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Health /></QueryClientProvider>);

describe('Health page', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows the environment, overall level, issues worst first and the five headline numbers', async () => {
    mockApi(report());
    renderPage();

    expect(await screen.findByText('dev')).toBeInTheDocument();
    expect(screen.getByText('Problem')).toBeInTheDocument();
    const issues = screen.getAllByRole('listitem').filter((li) => li.className.includes('issue'));
    expect(issues[0]).toHaveTextContent('38 incoming event(s) have been PROCESSING');
    expect(issues[2]).toHaveTextContent('No events arrived');

    const stuck = screen.getByText('Stuck work').closest('article')!;
    expect(within(stuck).getByText('38')).toBeInTheDocument();
    expect(within(stuck).getByText(/oldest 5d 22h/)).toBeInTheDocument();
    expect(within(stuck).getByText(/Older leftovers, not counted: 1247 events/)).toBeInTheDocument();
    expect(within(screen.getByText('Failure rate').closest('article')!).getByText('15%')).toBeInTheDocument();
    expect(within(screen.getByText('Automatic retries').closest('article')!).getByText('3')).toBeInTheDocument();
    expect(within(screen.getByText('Dead letters').closest('article')!).getByText('10')).toBeInTheDocument();
    expect(within(screen.getByText('CDS callbacks').closest('article')!).getByText('18%')).toBeInTheDocument();
  });

  it('lists failing events worst first and flags high rates', async () => {
    mockApi(report());
    renderPage();

    const rows = (await screen.findAllByText('policy.rmdtriggerpostapv.completed'))[0].closest('tr')!;
    expect(within(rows).getByText('59%')).toHaveClass('bad-text');
    expect(within(rows).getByText(/8 tech/)).toBeInTheDocument();
    expect(screen.getByText('SearchDocumentWorker')).toBeInTheDocument();
    expect(screen.getByText('brand.new.event')).toBeInTheDocument();
  });

  it('says all clear when there are no issues', async () => {
    mockApi(report({ level: 'ok', issues: [], new_event_types: [], workers: [] }));
    renderPage();

    expect(await screen.findByText('✓ Nothing needs attention.')).toBeInTheDocument();
    expect(screen.getByText('Healthy')).toBeInTheDocument();
    expect(screen.getByText('Nothing new in this window.')).toBeInTheDocument();
    expect(screen.getByText('No technical failures in this window.')).toBeInTheDocument();
  });

  it('explains when Mongo is not configured', async () => {
    mockApi({ status: 503, errors: ['Mongo is not configured (MONGODB_URI, MONGODB_DATABASE)'] });
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('Mongo is not configured');
  });
});

describe('formatting helpers', () => {
  it('formats percentages and minute spans', () => {
    expect([pct(null), pct(0), pct(0.182), pct(1)]).toEqual(['–', '0%', '18%', '100%']);
    expect([minutes(null), minutes(45), minutes(120), minutes(95), minutes(1440), minutes(8576)]).toEqual(['–', '45m', '2h', '1h 35m', '1d', '5d 22h']);
  });
});
