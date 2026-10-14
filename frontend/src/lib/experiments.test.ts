import { describe, expect, it } from 'vitest';
import { formatLift, formatP, formatRelative, niceTicks } from './experiments';

describe('experiment formatting', () => {
  it('formats conversion lift in percentage points and means in units', () => {
    expect(formatLift(0.0289, 'CONVERSION')).toBe('+2.89 pp');
    expect(formatLift(-0.004, 'CONVERSION')).toBe('−0.40 pp');
    expect(formatLift(1.8175, 'MEAN')).toBe('+1.82');
    expect(formatLift(0, 'MEAN')).toBe('±0.00');
  });

  it('formats relative lift and p-values', () => {
    expect(formatRelative(0.282)).toBe('+28.2%');
    expect(formatRelative(undefined)).toBe('—');
    expect(formatP(6.7e-6)).toBe('< 0.0001');
    expect(formatP(0.0354)).toBe('0.035');
    expect(formatP(0.0049)).toBe('0.0049');
  });

  it('produces nice ticks that cover the domain', () => {
    expect(niceTicks(-0.004, 0.043)).toEqual([0, 0.01, 0.02, 0.03, 0.04]);
    const t = niceTicks(-2.3, 2.6);
    expect(t[0]).toBeGreaterThanOrEqual(-2.3);
    expect(t.at(-1)).toBeLessThanOrEqual(2.6);
    expect(t).toContain(0);
  });
});
