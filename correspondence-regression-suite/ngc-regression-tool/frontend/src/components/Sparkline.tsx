import type { HistoryPoint } from '../lib/types';

/** Tiny trend line from stored health snapshots. */
export function Sparkline({ points, bad = false }: { points: HistoryPoint[] | undefined; bad?: boolean }) {
  if (!points || points.length < 2) return <span className="muted small">Trend appears after a few snapshots</span>;
  const w = 140;
  const h = 34;
  const vs = points.map((p) => p.metric_value);
  const min = Math.min(...vs);
  const max = Math.max(...vs);
  const span = max - min || 1;
  const path = points.map((p, i) => `${(i / (points.length - 1)) * w},${h - 3 - ((p.metric_value - min) / span) * (h - 6)}`).join(' ');
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} role="img" aria-label={`Trend over ${points.length} snapshots, latest ${vs[vs.length - 1]}`} className={bad ? 'spark bad' : 'spark'}>
      <polyline points={path} fill="none" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}
