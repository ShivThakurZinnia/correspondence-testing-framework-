/** Spring 6-field cron: second minute hour day month weekday. */
export const CRON_PRESETS: { label: string; cron: string }[] = [
  { label: 'Every day at 06:00', cron: '0 0 6 * * *' },
  { label: 'Every day at 02:00', cron: '0 0 2 * * *' },
  { label: 'Weekdays at 07:30', cron: '0 30 7 * * MON-FRI' },
  { label: 'Every 4 hours', cron: '0 0 */4 * * *' },
  { label: 'Every hour', cron: '0 0 * * * *' },
];

export function describeCron(cron: string): string {
  const daily = /^0 (\d{1,2}) (\d{1,2}) \* \* \*$/.exec(cron.trim());
  if (daily) return `Daily at ${daily[2].padStart(2, '0')}:${daily[1].padStart(2, '0')}`;
  const weekdays = /^0 (\d{1,2}) (\d{1,2}) \* \* MON-FRI$/.exec(cron.trim());
  if (weekdays) return `Weekdays at ${weekdays[2].padStart(2, '0')}:${weekdays[1].padStart(2, '0')}`;
  const every = /^0 0 \*\/(\d{1,2}) \* \* \*$/.exec(cron.trim());
  if (every) return `Every ${every[1]} hours`;
  if (cron.trim() === '0 0 * * * *') return 'Every hour';
  return cron;
}

export const isSixFieldCron = (cron: string): boolean => cron.trim().split(/\s+/).length === 6;
