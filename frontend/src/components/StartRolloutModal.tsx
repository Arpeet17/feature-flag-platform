import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useStartRollout } from '../api/hooks';
import type { Flag } from '../api/types';
import { variationLabel } from '../lib/targeting';
import { ErrorBanner, Field, Modal } from './ui';

const PRESETS: Record<string, number[]> = {
  'Cautious (1 → 5 → 25 → 50 → 100)': [1, 5, 25, 50, 100],
  'Standard (10 → 25 → 50 → 100)': [10, 25, 50, 100],
  'Quick (50 → 100)': [50, 100],
};
const INTERVALS: [string, number][] = [['15 seconds (demo)', 15], ['5 minutes', 300], ['1 hour', 3600], ['1 day', 86400]];

export function StartRolloutModal({ projectKey, flag, envKey, envName, onClose }: {
  projectKey: string; flag: Flag; envKey: string; envName: string; onClose: () => void;
}) {
  const start = useStartRollout(projectKey, flag.key);
  const navigate = useNavigate();
  const [treatment, setTreatment] = useState(flag.defaultOnVariation);
  const [control, setControl] = useState(flag.defaultOffVariation === flag.defaultOnVariation ? 1 : flag.defaultOffVariation);
  const [preset, setPreset] = useState(Object.keys(PRESETS)[0]);
  const [interval, setInterval] = useState(3600);
  const [minUsers, setMinUsers] = useState(100);
  const [guardrail, setGuardrail] = useState('error');

  return (
    <Modal title={`Guarded rollout in ${envName}`} onClose={onClose} footer={
      <>
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={treatment === control || start.isPending}
                onClick={() => start.mutate({ envKey, body: {
                  treatmentVariation: treatment, controlVariation: control, steps: PRESETS[preset], stepIntervalSeconds: interval,
                  minUsersPerStep: minUsers, guardrailEventKey: guardrail.trim() || undefined,
                } }, { onSuccess: (r) => navigate(`/projects/${projectKey}/rollouts/${r.id}`) })}>
          Start rollout
        </button>
      </>
    }>
      <ErrorBanner error={start.error} />
      <p className="muted">The flag's default rule is ramped step by step (rules and individual targets are untouched).
        The flag is turned on at the first step.</p>
      <div className="form-grid">
        <Field label="Roll out">
          <select value={treatment} onChange={(e) => setTreatment(Number(e.target.value))}>
            {flag.variations.map((_, i) => <option key={i} value={i}>{variationLabel(flag.variations, i)}</option>)}
          </select>
        </Field>
        <Field label="Instead of (control)">
          <select value={control} onChange={(e) => setControl(Number(e.target.value))}>
            {flag.variations.map((_, i) => <option key={i} value={i}>{variationLabel(flag.variations, i)}</option>)}
          </select>
        </Field>
      </div>
      <div className="form-grid">
        <Field label="Steps"><select value={preset} onChange={(e) => setPreset(e.target.value)}>{Object.keys(PRESETS).map((p) => <option key={p}>{p}</option>)}</select></Field>
        <Field label="Time per step"><select value={interval} onChange={(e) => setInterval(Number(e.target.value))}>{INTERVALS.map(([l, v]) => <option key={v} value={v}>{l}</option>)}</select></Field>
      </div>
      <div className="form-grid">
        <Field label="Guardrail event" hint="Rolled back if its rate is significantly higher for the new variation. Empty = time-based only.">
          <input value={guardrail} onChange={(e) => setGuardrail(e.target.value)} placeholder="error" />
        </Field>
        <Field label="Min. treatment users per step"><input type="number" min={0} value={minUsers} onChange={(e) => setMinUsers(Number(e.target.value))} /></Field>
      </div>
    </Modal>
  );
}
