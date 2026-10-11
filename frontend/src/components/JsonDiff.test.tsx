import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { JsonDiff, diffLines } from './JsonDiff';

describe('diffLines', () => {
  it('marks added and removed lines around common ones', () => {
    expect(diffLines('a\nb\nc', 'a\nx\nc')).toEqual([
      { kind: 'same', text: 'a' },
      { kind: 'removed', text: 'b' },
      { kind: 'added', text: 'x' },
      { kind: 'same', text: 'c' },
    ]);
  });

  it('handles creation and deletion', () => {
    expect(diffLines('', 'a')).toEqual([{ kind: 'added', text: 'a' }]);
    expect(diffLines('a', '')).toEqual([{ kind: 'removed', text: 'a' }]);
  });
});

describe('JsonDiff', () => {
  it('renders a toggle change', () => {
    render(<JsonDiff before={{ enabled: false }} after={{ enabled: true }} />);
    const diff = screen.getByLabelText('Change diff');
    expect(diff.querySelector('.diff-removed')).toHaveTextContent('"enabled": false');
    expect(diff.querySelector('.diff-added')).toHaveTextContent('"enabled": true');
  });
});
