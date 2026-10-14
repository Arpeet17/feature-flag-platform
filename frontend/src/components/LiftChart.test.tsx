import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Comparison } from '../api/types';
import { LiftChart } from './LiftChart';

class ResizeObserverStub {
  observe() {}
  disconnect() {}
}
globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;

const cmp = (variation: number, label: string, lift: number, verdict: Comparison['verdict']): Comparison => ({
  variation, label, absoluteLift: lift, relativeLift: lift / 0.1, ciLow: lift - 0.01, ciHigh: lift + 0.01,
  pValue: 0.01, significant: verdict !== 'INCONCLUSIVE', verdict,
});

describe('LiftChart', () => {
  it('labels every verdict in words, not just color', () => {
    render(<LiftChart metricType="CONVERSION"
                      comparisons={[cmp(1, 'Semantic', 0.002, 'INCONCLUSIVE'), cmp(2, 'Hybrid', 0.029, 'BETTER'), cmp(3, 'Legacy', -0.03, 'WORSE')]} />);
    expect(screen.getByText('Better')).toBeInTheDocument();
    expect(screen.getByText('Worse')).toBeInTheDocument();
    expect(screen.getByText('Inconclusive')).toBeInTheDocument();
    expect(screen.getByRole('img').getAttribute('aria-label')).toContain('Hybrid +2.90 pp (Better)');
  });

  it('waits for data instead of plotting underpowered comparisons', () => {
    render(<LiftChart metricType="CONVERSION" comparisons={[cmp(1, 'B', 0.1, 'INSUFFICIENT_DATA')]} />);
    expect(screen.getByText(/Lift will appear/)).toBeInTheDocument();
  });
});

describe('LiftChart with a sample ratio mismatch', () => {
  it('replaces verdicts with an untrusted marker', () => {
    render(<LiftChart metricType="MEAN" untrusted comparisons={[cmp(0, 'On', 1.8, 'BETTER')]} />);
    expect(screen.queryByText('Better')).not.toBeInTheDocument();
    expect(screen.getByText('Untrusted (SRM)')).toBeInTheDocument();
  });
});
