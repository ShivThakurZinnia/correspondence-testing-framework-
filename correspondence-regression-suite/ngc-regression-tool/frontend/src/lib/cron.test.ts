import { describe, expect, it } from 'vitest';
import { describeCron, isSixFieldCron } from './cron';

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
