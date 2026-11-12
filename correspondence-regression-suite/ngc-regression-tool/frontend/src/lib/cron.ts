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

/**
 * A daily cron that fires at the next whole minute in the given time zone, at least `leadSeconds` from now, so a save
 * takes effect almost immediately. Returns null for an unknown time zone.
 */
export function nextMinuteCron(timezone: string, now: number = Date.now(), leadSeconds = 30): { cron: string; time: string } | null {
  const target = new Date(Math.ceil((now + leadSeconds * 1000) / 60000) * 60000);
  try {
    const parts = new Intl.DateTimeFormat('en-GB', { timeZone: timezone.trim(), hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(target);
    const hour = Number(parts.find((p) => p.type === 'hour')?.value);
    const minute = Number(parts.find((p) => p.type === 'minute')?.value);
    if (Number.isNaN(hour) || Number.isNaN(minute)) return null;
    return { cron: `0 ${minute} ${hour} * * *`, time: `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}` };
  } catch {
    return null;
  }
}
