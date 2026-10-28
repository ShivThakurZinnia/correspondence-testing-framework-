import { describe, expect, it } from 'vitest';
import { buildExpected, emptyExpected, GILI_POLICY_PAGE_TEMPLATE, JOURNEY, JOURNEY_STEPS, parseExpected } from './expected';

describe('expected outcome model', () => {
  it('the default asserts the final status, journey and no failures', () => {
    expect(buildExpected(emptyExpected())).toEqual({
      incomingEvent: { status: 'SENT_TO_CDS' },
      targets: [{ role: 'OWNER', status: 'SENT_TO_CDS' }],
      cds: { published: true },
      journey: ['requestValid', 'requestStart', 'requestSentToCDS'],
      noFailures: ['technical_failures', 'dead_letter_events'],
    });
  });

  it('round-trips the GILI template through JSON', () => {
    const json = JSON.stringify(buildExpected(GILI_POLICY_PAGE_TEMPLATE));
    expect(parseExpected(json)).toEqual(GILI_POLICY_PAGE_TEMPLATE);
    expect(JSON.parse(json).targets[0]).toEqual({ role: 'OWNER', status: 'SENT_TO_CDS', deliveryType: 'PRINT', documentType: 'POLPG', xmlFileName: 'present' });
  });

  it('keeps xml options and ignore list', () => {
    const s = { ...emptyExpected(), xml: { on: true, role: '', exists: true, xsdValid: true, goldenCompare: true, ignore: 'CREATED, A/B' } };
    expect(buildExpected(s).xml).toEqual({ exists: true, xsdValid: true, goldenCompare: true, ignore: ['CREATED', 'A/B'] });
    expect(parseExpected(JSON.stringify(buildExpected(s)))?.xml.ignore).toBe('CREATED, A/B');
  });

  it('refuses JSON the form cannot represent so nothing is silently dropped', () => {
    expect(parseExpected('{"incomingEvent":{"status":"X"},"cds":{"fields":{"ClientCode":"GILI"}}}')).toBeNull();
    expect(parseExpected('{"incomingEvent":{"status":"X"},"targets":[{"role":"OWNER","tangoJobIdPresent":true}]}')).toBeNull();
    expect(parseExpected('{"incomingEvent":{"status":"X"},"somethingNew":1}')).toBeNull();
    expect(parseExpected('not json')).toBeNull();
    expect(parseExpected('[]')).toBeNull();
  });

  it('offers exactly the NGC journey steps that NGC can record for an event, each with an explanation', () => {
    expect(JOURNEY).toEqual([
      'requestValid', 'requestStart', 'requestSentToCDS', 'requestSentToCorroEmailService', 'requestSentToMerrill',
      'caseStatusCompleted',
    ]);
    expect(JOURNEY_STEPS.every((j) => j.help.length > 20)).toBe(true);
    expect(emptyExpected().journey).toEqual(['requestValid', 'requestStart', 'requestSentToCDS']);
  });
});
