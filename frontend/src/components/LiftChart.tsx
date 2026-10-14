import { useEffect, useRef, useState } from 'react';
import type { Comparison, MetricType } from '../api/types';
import { formatLift, formatP, formatRelative, formatTick, niceTicks, verdictFor } from '../lib/experiments';

const ROW = 40;
const AXIS = 28;
const PAD = 12;

/**
 * Forest plot: each treatment's lift over control with its confidence interval on one shared
 * axis; the zero line is "same as control". An interval that crosses zero is not significant.
 */
export function LiftChart({ comparisons, metricType, untrusted = false }: { comparisons: Comparison[]; metricType: MetricType; untrusted?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(480);
  const [hover, setHover] = useState<number | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([entry]) => setWidth(Math.max(240, entry.contentRect.width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const rows = comparisons.filter((c) => c.verdict !== 'INSUFFICIENT_DATA');
  if (rows.length === 0) return <p className="muted">Lift will appear once every variation has enough data.</p>;

  const lo = Math.min(0, ...rows.map((c) => c.ciLow));
  const hi = Math.max(0, ...rows.map((c) => c.ciHigh));
  const pad = (hi - lo) * 0.08 || 0.01;
  const ticks = niceTicks(lo - pad, hi + pad);
  const dMin = Math.min(lo - pad, ticks[0]);
  const dMax = Math.max(hi + pad, ticks[ticks.length - 1]);
  const x = (v: number) => PAD + ((v - dMin) / (dMax - dMin)) * (width - 2 * PAD);
  const height = rows.length * ROW + AXIS;

  return (
    <div className="lift-chart">
      <div className="lift-labels" aria-hidden>
        {rows.map((c, i) => (
          <div key={c.variation} className={`lift-label ${hover === i ? 'is-hover' : ''}`} style={{ height: ROW }}>
            {c.label}
          </div>
        ))}
      </div>
      <div className="lift-plot" ref={ref}>
        <svg width={width} height={height} role="img"
             aria-label={`Lift versus control: ${rows.map((c) => `${c.label} ${formatLift(c.absoluteLift, metricType)} (${verdictFor(c.verdict, untrusted).label})`).join('; ')}`}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={x(t)} x2={x(t)} y1={0} y2={rows.length * ROW} className="grid-line" />
              <text x={x(t)} y={rows.length * ROW + 18} className="axis-label" textAnchor="middle">{formatTick(t, metricType)}</text>
            </g>
          ))}
          <line x1={x(0)} x2={x(0)} y1={0} y2={rows.length * ROW} className="zero-line" />
          {rows.map((c, i) => {
            const cy = i * ROW + ROW / 2;
            const v = verdictFor(c.verdict, untrusted);
            const cx = x(c.absoluteLift);
            return (
              <g key={c.variation} className={`lift-row tone-${v.tone}`} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
                <rect x={0} y={i * ROW} width={width} height={ROW} className="hit" />
                <line x1={x(c.ciLow)} x2={x(c.ciHigh)} y1={cy} y2={cy} className="ci" />
                {v.shape === 'up' && <path d={`M${cx},${cy - 7} L${cx + 7},${cy + 5} L${cx - 7},${cy + 5} Z`} className="mark" />}
                {v.shape === 'down' && <path d={`M${cx},${cy + 7} L${cx + 7},${cy - 5} L${cx - 7},${cy - 5} Z`} className="mark" />}
                {v.shape === 'circle' && <circle cx={cx} cy={cy} r={5} className="mark" />}
              </g>
            );
          })}
        </svg>
        {hover !== null && rows[hover] && (
          <div className="chart-tooltip" style={{ left: Math.min(width - 200, Math.max(0, x(rows[hover].absoluteLift) - 100)), top: hover * ROW + ROW }}>
            <strong>{rows[hover].label}</strong>
            <div>Lift {formatLift(rows[hover].absoluteLift, metricType)} ({formatRelative(rows[hover].relativeLift)})</div>
            <div className="muted">CI {formatLift(rows[hover].ciLow, metricType)} … {formatLift(rows[hover].ciHigh, metricType)}</div>
            <div className="muted">p = {formatP(rows[hover].pValue)}</div>
          </div>
        )}
      </div>
      <div className="lift-verdicts">
        {rows.map((c, i) => {
          const v = verdictFor(c.verdict, untrusted);
          return (
            <div key={c.variation} className={`lift-verdict tone-${v.tone} ${hover === i ? 'is-hover' : ''}`} style={{ height: ROW }}>
              <span aria-hidden>{v.icon}</span> {v.label}
            </div>
          );
        })}
      </div>
    </div>
  );
}
