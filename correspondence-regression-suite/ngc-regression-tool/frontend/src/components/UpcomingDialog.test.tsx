import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { UpcomingDialog } from './UpcomingDialog';

const base = { env: 'dev', module: null, cron_expression: '0 0 6 * * *', timezone: 'UTC', next_fire_at: '2099-01-01T06:00:00Z', executing: false };

function renderDialog(rows: unknown[]) {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(rows), { status: 200 })));
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter><UpcomingDialog onClose={() => {}} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('UpcomingDialog', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows the run time and reveals the scenarios on expand', async () => {
    renderDialog([{ ...base, id: 1, name: 'nightly', tag_expression: 'client:GILI', events: 3,
      scenarios: [{ scenario_key: 'k', scenario_name: 'Owner print', carrier: 'GILI', event_type: 'policy', plan_codes: ['A1', 'B2'], events: 2 }] }]);
    expect(await screen.findByText('nightly')).toBeTruthy();
    expect(screen.queryByText('Owner print')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: /1 scenario · 3 events/ }));
    expect(screen.getByText('Owner print')).toBeTruthy();
    expect(screen.getByText('A1')).toBeTruthy();
  });

  it('says why a schedule would not start', async () => {
    renderDialog([{ ...base, id: 2, name: 'broken', tag_expression: 'client:X', events: 0, scenarios: [], problem: 'No enabled test cases match the selection' }]);
    expect(await screen.findByText(/would not start: No enabled test cases/)).toBeTruthy();
  });
});
