import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { buildExpected, emptyExpected } from '../lib/expected';
import { ExpectedBuilder } from './ExpectedBuilder';

function Harness({ initial }: { initial: string }) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <ExpectedBuilder value={value} onChange={setValue} />
      <pre data-testid="out">{value}</pre>
    </>
  );
}
const out = () => JSON.parse(screen.getByTestId('out').textContent!);

describe('ExpectedBuilder', () => {
  it('edits the expected JSON through the form', async () => {
    const user = userEvent.setup();
    render(<Harness initial={JSON.stringify(buildExpected(emptyExpected()))} />);

    await user.selectOptions(screen.getByLabelText('Delivery'), 'EMAIL');
    await user.click(screen.getByRole('button', { name: 'caseStatusCompleted' }));
    await user.click(screen.getByRole('button', { name: 'pdf.generation.completed' }));
    await user.click(screen.getByLabelText('No technical failure recorded'));

    expect(out()).toMatchObject({
      targets: [{ role: 'OWNER', deliveryType: 'EMAIL' }],
      journey: ['requestValid', 'requestStart', 'requestSentToCDS', 'caseStatusCompleted'],
      callbacks: ['pdf.generation.completed'],
      noFailures: ['dead_letter_events'],
    });
  });

  it('adds and removes parties', async () => {
    const user = userEvent.setup();
    render(<Harness initial={JSON.stringify(buildExpected(emptyExpected()))} />);

    await user.click(screen.getByRole('button', { name: '+ Add party' }));
    expect(out().targets).toHaveLength(2);
    await user.click(screen.getAllByRole('button', { name: 'Remove party' })[0]);
    expect(out().targets).toHaveLength(1);
  });

  it('falls back to the JSON editor for options the form cannot show', () => {
    render(<Harness initial={'{"incomingEvent":{"status":"X"},"cds":{"fields":{"ClientCode":"GILI"}}}'} />);

    expect(screen.getByLabelText('Expected JSON')).toBeInTheDocument();
    expect(screen.getByText(/uses options the form cannot show/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Form' })).toBeDisabled();
  });

  it('the GILI template fills the form', async () => {
    const user = userEvent.setup();
    render(<Harness initial={JSON.stringify(buildExpected(emptyExpected()))} />);

    await user.click(screen.getByRole('button', { name: 'Use GILI policy page template' }));

    expect(out().targets[0]).toMatchObject({ deliveryType: 'PRINT', documentType: 'POLPG', xmlFileName: 'present' });
    expect(out().cds).toEqual({ published: true, count: 1 });
  });
});
