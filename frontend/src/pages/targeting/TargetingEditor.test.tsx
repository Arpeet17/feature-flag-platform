import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/client';
import type { Flag, FlagConfig } from '../../api/types';
import { TargetingEditor } from './TargetingEditor';

const config: FlagConfig = {
  environmentKey: 'production',
  environmentName: 'Production',
  enabled: false,
  targeting: { offVariation: 1, prerequisites: [], targets: [], rules: [], fallthrough: { variation: 0 } },
  version: 3,
  updatedBy: 'admin@ffp.local',
  updatedAt: new Date().toISOString(),
  usage: { total: 0, byVariation: {}, lastEvaluatedAt: null },
};

const flag = {
  key: 'new-checkout',
  name: 'New checkout',
  type: 'BOOLEAN',
  variations: [{ value: true, name: 'On' }, { value: false, name: 'Off' }],
  environments: [config],
} as unknown as Flag;

function renderEditor(overrides: Partial<Parameters<typeof TargetingEditor>[0]> = {}) {
  const onSave = vi.fn();
  render(
    <TargetingEditor flag={flag} config={config} envName="Production" segmentKeys={['beta']} otherFlags={[]}
                     canEdit saving={false} error={null} onSave={onSave} onReload={vi.fn()} {...overrides} />,
  );
  return { onSave };
}

describe('TargetingEditor', () => {
  it('shows no save bar until something changes', () => {
    renderEditor();
    expect(screen.queryByRole('region', { name: 'Unsaved changes' })).not.toBeInTheDocument();
  });

  it('saves a percentage rollout with the version it was based on', async () => {
    const { onSave } = renderEditor();
    await userEvent.click(screen.getByRole('switch', { name: 'Enable flag in Production' }));
    await userEvent.selectOptions(screen.getByLabelText('Default rule serve'), 'rollout');

    const on = screen.getByLabelText('Default rule serve: percentage for On');
    const off = screen.getByLabelText('Default rule serve: percentage for Off');
    await userEvent.clear(on);
    await userEvent.type(on, '10');
    await userEvent.clear(off);
    await userEvent.type(off, '90');
    await userEvent.type(screen.getByLabelText('Change comment'), 'ramp to 10%');
    await userEvent.click(screen.getByRole('button', { name: 'Save to Production' }));

    expect(onSave).toHaveBeenCalledWith({
      enabled: true,
      version: 3,
      comment: 'ramp to 10%',
      targeting: expect.objectContaining({
        fallthrough: { rollout: { bucketBy: 'key', variations: [{ variation: 0, weight: 10_000 }, { variation: 1, weight: 90_000 }] } },
      }),
    });
  });

  it('blocks saving while the rollout does not add up to 100%', async () => {
    renderEditor();
    await userEvent.selectOptions(screen.getByLabelText('Default rule serve'), 'rollout');
    const on = screen.getByLabelText('Default rule serve: percentage for On');
    await userEvent.clear(on);
    await userEvent.type(on, '70');
    const bar = screen.getByRole('region', { name: 'Unsaved changes' });
    expect(within(bar).getByText(/must be 100%/)).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Save to Production' })).toBeDisabled();
  });

  it('offers to reload when the server reports a conflicting edit', async () => {
    const onReload = vi.fn();
    renderEditor({ error: new ApiError(409, 'Conflict', 'Targeting was changed by bob@acme.com'), onReload });
    await userEvent.click(screen.getByRole('button', { name: 'Load latest' }));
    expect(screen.getByText(/Someone else changed this flag/)).toBeInTheDocument();
    expect(onReload).toHaveBeenCalled();
  });

  it('is read-only for viewers', () => {
    renderEditor({ canEdit: false });
    expect(screen.getByRole('switch', { name: 'Enable flag in Production' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: '+ Rule' })).not.toBeInTheDocument();
  });
});
