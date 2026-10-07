import type { Variation, VariationOrRollout } from '../../api/types';
import { TOTAL_WEIGHT, evenRollout, formatPercent, percentToWeight, rolloutTotal, variationLabel, weightToPercent } from '../../lib/targeting';

const ROLLOUT = 'rollout';

/** Chooses what a rule serves: a fixed variation or a percentage rollout across variations. */
export function ServeEditor({ value, onChange, variations, disabled, label }: {
  value: VariationOrRollout;
  onChange: (v: VariationOrRollout) => void;
  variations: Variation[];
  disabled?: boolean;
  label: string;
}) {
  const rollout = value.rollout;
  const selected = rollout ? ROLLOUT : String(value.variation ?? '');

  const setWeight = (variation: number, percent: number) => {
    if (!rollout) return;
    const weight = Math.max(0, Math.min(TOTAL_WEIGHT, percentToWeight(percent)));
    const existing = rollout.variations.some((v) => v.variation === variation);
    const variationsNext = existing
      ? rollout.variations.map((v) => (v.variation === variation ? { ...v, weight } : v))
      : [...rollout.variations, { variation, weight }];
    onChange({ rollout: { ...rollout, variations: variationsNext } });
  };

  const total = rollout ? rolloutTotal(rollout) : TOTAL_WEIGHT;

  return (
    <div className="serve-editor">
      <select aria-label={label} value={selected} disabled={disabled}
              onChange={(e) => onChange(e.target.value === ROLLOUT
                ? { rollout: evenRollout(variations.length) }
                : { variation: Number(e.target.value) })}>
        {variations.map((_, i) => <option key={i} value={i}>Serve {variationLabel(variations, i)}</option>)}
        <option value={ROLLOUT}>Percentage rollout…</option>
      </select>

      {rollout && (
        <div className="rollout">
          <div className="rollout-bar" aria-hidden>
            {rollout.variations.filter((v) => v.weight > 0).map((v) => (
              <span key={v.variation} className={`rollout-seg seg-${v.variation % 6}`} style={{ width: `${(v.weight / TOTAL_WEIGHT) * 100}%` }}
                    title={`${variationLabel(variations, v.variation)} ${formatPercent(v.weight)}`} />
            ))}
          </div>
          {variations.map((_, i) => {
            const weight = rollout.variations.find((v) => v.variation === i)?.weight ?? 0;
            return (
              <label key={i} className="rollout-row">
                <span className={`swatch seg-${i % 6}`} aria-hidden />
                <span className="rollout-label">{variationLabel(variations, i)}</span>
                <input type="number" min={0} max={100} step={0.001} disabled={disabled}
                       aria-label={`${label}: percentage for ${variationLabel(variations, i)}`}
                       value={weightToPercent(weight)} onChange={(e) => setWeight(i, Number(e.target.value))} />
                <span>%</span>
              </label>
            );
          })}
          <div className={`rollout-total ${total === TOTAL_WEIGHT ? '' : 'invalid'}`}>
            Total {formatPercent(total)}{total !== TOTAL_WEIGHT && ' — must equal 100%'}
          </div>
          <label className="rollout-row bucket-by">
            <span className="rollout-label">Bucket by attribute</span>
            <input value={rollout.bucketBy ?? 'key'} disabled={disabled} aria-label={`${label}: bucket by`}
                   onChange={(e) => onChange({ rollout: { ...rollout, bucketBy: e.target.value } })} />
          </label>
        </div>
      )}
    </div>
  );
}
