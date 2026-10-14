import type { GuardedRollout } from '../api/types';

/**
 * Step tracker: one segment per step, filled up to the current one. Status is carried by the
 * text label beside it (never color alone); a rolled-back rollout shows where it stopped.
 */
export function RolloutProgress({ rollout }: { rollout: GuardedRollout }) {
  const { steps, currentStep, status } = rollout;
  return (
    <div className={`rollout-progress status-${status.toLowerCase()}`}
         role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={rollout.currentPercentage}
         aria-label={`Rollout of ${rollout.treatmentLabel}: step ${currentStep + 1} of ${steps.length}, ${rollout.currentPercentage}%, ${status.toLowerCase().replace('_', ' ')}`}>
      {steps.map((pct, i) => {
        const state = i < currentStep || (i === currentStep && status === 'COMPLETED') ? 'done'
          : i === currentStep ? 'current' : 'todo';
        return (
          <div key={pct} className={`rp-step rp-${state}`}>
            <div className="rp-bar" />
            <div className="rp-label">{pct}%</div>
          </div>
        );
      })}
    </div>
  );
}
