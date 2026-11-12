import { describe, expect, it } from 'vitest';
import { describeCron, isSixFieldCron, nextMinuteCron } from './cron';

describe('cron helpers', () => {
  it('describes common schedules and falls back to the raw expression', () => {
    expect(describeCron('0 0 6 * * *')).toBe('Daily at 06:00');
    expect(describeCron('0 30 7 * * MON-FRI')).toBe('Weekdays at 07:30');
    expect(describeCron('0 0 */4 * * *')).toBe('Every 4 hours');
    expect(describeCron('0 0 * * * *')).toBe('Every hour');
    expect(describeCron('0 15 10 15 * ?')).toBe('0 15 10 15 * ?');
  });
  it('needs exactly six fields', () => {
    expect(isSixFieldCron('0 0 6 * * *')).toBe(true);
    expect(isSixFieldCron('0 6 * * *')).toBe(false);
  });
});

describe('nextMinuteCron', () => {
  const now = Date.parse('2026-11-12T10:00:10Z');
  it('fires at the next whole minute that is at least 30 seconds away', () => {
    expect(nextMinuteCron('UTC', now)).toEqual({ cron: '0 1 10 * * *', time: '10:01' });
    expect(nextMinuteCron('UTC', Date.parse('2026-11-12T10:00:40Z'))).toEqual({ cron: '0 2 10 * * *', time: '10:02' });
  });
  it('uses the clock of the schedule time zone and rolls over midnight', () => {
    expect(nextMinuteCron('Asia/Kolkata', now)).toEqual({ cron: '0 31 15 * * *', time: '15:31' });
    expect(nextMinuteCron('UTC', Date.parse('2026-11-12T23:59:20Z'))).toEqual({ cron: '0 0 0 * * *', time: '00:00' });
  });
  it('gives up on an unknown time zone', () => {
    expect(nextMinuteCron('Mars/Base', now)).toBeNull();
  });
});
