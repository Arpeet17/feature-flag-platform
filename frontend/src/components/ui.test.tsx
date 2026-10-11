import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { Toggle, ValuesInput } from './ui';

function Harness({ initial = [] as string[] }) {
  const [values, setValues] = useState(initial);
  return <ValuesInput ariaLabel="Values" values={values} onChange={setValues} />;
}

describe('ValuesInput', () => {
  it('adds values on Enter and comma, ignoring duplicates', async () => {
    render(<Harness />);
    const input = screen.getByLabelText('Values');
    await userEvent.type(input, 'US{Enter}CA,US{Enter}');
    expect(screen.getAllByRole('button', { name: /Remove/ }).map((b) => b.getAttribute('aria-label')))
      .toEqual(['Remove US', 'Remove CA']);
  });

  it('removes the last value with Backspace on an empty input', async () => {
    render(<Harness initial={['a', 'b']} />);
    await userEvent.type(screen.getByLabelText('Values'), '{Backspace}');
    expect(screen.queryByRole('button', { name: 'Remove b' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove a' })).toBeInTheDocument();
  });
});

describe('Toggle', () => {
  it('exposes switch semantics and reports the next state', async () => {
    const onChange = vi.fn();
    render(<Toggle checked={false} onChange={onChange} label="Enable flag" />);
    const toggle = screen.getByRole('switch', { name: 'Enable flag' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    await userEvent.click(toggle);
    expect(onChange).toHaveBeenCalledWith(true);
  });
});
