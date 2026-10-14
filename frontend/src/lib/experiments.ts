import type { MetricType, Verdict } from '../api/types';

/** Lift in percentage points for conversion metrics, absolute units for means. */
export function formatLift(value: number, type: MetricType): string {
  const sign = value > 0 ? '+' : value < 0 ? '−' : '±';
  const abs = Math.abs(value);
  return type === 'CONVERSION' ? `${sign}${(abs * 100).toFixed(2)} pp` : `${sign}${abs.toFixed(2)}`;
}

export function formatEstimate(value: number, type: MetricType): string {
  return type === 'CONVERSION' ? `${(value * 100).toFixed(2)}%` : value.toFixed(2);
}

export function formatRelative(value: number | undefined): string {
  if (value === undefined || !Number.isFinite(value)) return '—';
  return `${value > 0 ? '+' : value < 0 ? '−' : ''}${Math.abs(value * 100).toFixed(1)}%`;
}

export function formatP(p: number): string {
  if (p < 0.0001) return '< 0.0001';
  return p < 0.01 ? p.toFixed(4) : p.toFixed(3);
}

/** Verdicts are states: each pairs a status color with a shape, an icon and a word, never color alone. */
export const VERDICT: Record<Verdict, { label: string; icon: string; tone: 'success' | 'danger' | 'neutral'; shape: 'up' | 'down' | 'circle' }> = {
  BETTER: { label: 'Better', icon: '▲', tone: 'success', shape: 'up' },
  WORSE: { label: 'Worse', icon: '▼', tone: 'danger', shape: 'down' },
  INCONCLUSIVE: { label: 'Inconclusive', icon: '●', tone: 'neutral', shape: 'circle' },
  INSUFFICIENT_DATA: { label: 'Not enough data', icon: '○', tone: 'neutral', shape: 'circle' },
};

/** Shown instead of any verdict when the sample ratio check fails: the data itself is broken. */
export const UNTRUSTED = { label: 'Untrusted (SRM)', icon: '⚠', tone: 'neutral', shape: 'circle' } as const;

export function verdictFor(verdict: Verdict, sampleRatioMismatch: boolean) {
  return sampleRatioMismatch && verdict !== 'INSUFFICIENT_DATA' ? UNTRUSTED : VERDICT[verdict];
}

export function formatTick(value: number, type: MetricType): string {
  return value === 0 ? '0' : formatLift(value, type);
}

/** "Nice" axis ticks covering [min, max]. */
export function niceTicks(min: number, max: number, target = 5): number[] {
  const span = max - min || Math.abs(max) || 1;
  const raw = span / target;
  const magnitude = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((s) => s >= raw) ?? magnitude * 10;
  const ticks: number[] = [];
  for (let t = Math.ceil(min / step) * step; t <= max + step * 1e-9; t += step) ticks.push(Math.round(t / step) * step || 0); // || 0 turns -0 into 0
  return ticks;
}
