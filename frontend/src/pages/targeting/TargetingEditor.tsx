import { useEffect, useMemo, useState } from 'react';
import type { Flag, FlagConfig, FlagSummary, Rule, Targeting } from '../../api/types';
import { ApiError } from '../../api/client';
import { ErrorBanner, Toggle, ValuesInput } from '../../components/ui';
import { newClause, validateTargeting, variationLabel } from '../../lib/targeting';
import { ClauseEditor } from './ClauseEditor';
import { ServeEditor } from './ServeEditor';

export interface SaveRequest {
  targeting: Targeting;
  enabled: boolean;
  version: number;
  comment?: string;
}

/**
 * Edits a flag's targeting for one environment as a local draft, saved atomically with the
 * version it was based on. If someone else saved in the meantime the server answers 409 and
 * the user can reload rather than silently overwriting their change.
 */
export function TargetingEditor({ flag, config, segmentKeys, otherFlags, canEdit, saving, error, onSave, onReload, envName }: {
  flag: Flag;
  config: FlagConfig;
  segmentKeys: string[];
  otherFlags: FlagSummary[];
  canEdit: boolean;
  saving: boolean;
  error: unknown;
  onSave: (req: SaveRequest) => void;
  onReload: () => void;
  envName: string;
}) {
  const [draft, setDraft] = useState<Targeting>(config.targeting);
  const [enabled, setEnabled] = useState(config.enabled);
  const [comment, setComment] = useState('');

  // Reset the draft whenever the server state changes (save, reload, environment switch).
  useEffect(() => {
    setDraft(config.targeting);
    setEnabled(config.enabled);
    setComment('');
  }, [config.targeting, config.enabled, config.version, config.environmentKey]);

  const dirty = useMemo(
    () => enabled !== config.enabled || JSON.stringify(draft) !== JSON.stringify(config.targeting),
    [draft, enabled, config],
  );
  const problems = useMemo(() => validateTargeting(draft, flag.variations.length), [draft, flag.variations.length]);
  const conflict = error instanceof ApiError && error.isConflict;
  const variations = flag.variations;
  const readOnly = !canEdit;

  const update = (patch: Partial<Targeting>) => setDraft((d) => ({ ...d, ...patch }));
  const updateRule = (i: number, rule: Rule) => update({ rules: draft.rules.map((r, j) => (j === i ? rule : r)) });
  const moveRule = (i: number, delta: number) => {
    const rules = [...draft.rules];
    const [rule] = rules.splice(i, 1);
    rules.splice(i + delta, 0, rule);
    update({ rules });
  };

  const targetsFor = (variation: number) => draft.targets.find((t) => t.variation === variation)?.values ?? [];
  const setTargets = (variation: number, values: string[]) => {
    const others = draft.targets.filter((t) => t.variation !== variation);
    update({ targets: values.length ? [...others, { variation, values }].sort((a, b) => a.variation - b.variation) : others });
  };

  return (
    <div className="targeting">
      <section className="card section">
        <div className="section-head">
          <div>
            <h3>Flag is {enabled ? 'ON' : 'OFF'} in {envName}</h3>
            <p className="muted">
              {enabled ? 'Targeting below decides what each context receives.' : `Everyone receives ${variationLabel(variations, draft.offVariation)}.`}
            </p>
          </div>
          <Toggle checked={enabled} onChange={setEnabled} disabled={readOnly} label={`Enable flag in ${envName}`} />
        </div>
        <label className="inline-field">
          When off, serve
          <select aria-label="Off variation" value={draft.offVariation} disabled={readOnly}
                  onChange={(e) => update({ offVariation: Number(e.target.value) })}>
            {variations.map((_, i) => <option key={i} value={i}>{variationLabel(variations, i)}</option>)}
          </select>
        </label>
      </section>

      <section className={`card section ${enabled ? '' : 'dimmed'}`}>
        <div className="section-head">
          <div>
            <h3>Prerequisites</h3>
            <p className="muted">Only evaluate this flag if other flags serve a specific variation.</p>
          </div>
          {!readOnly && (
            <button className="btn btn-sm" disabled={otherFlags.length === 0}
                    onClick={() => update({ prerequisites: [...draft.prerequisites, { flagKey: otherFlags[0].key, variation: 0 }] })}>
              + Prerequisite
            </button>
          )}
        </div>
        {draft.prerequisites.map((p, i) => {
          const prereqFlag = otherFlags.find((f) => f.key === p.flagKey);
          return (
            <div key={i} className="inline-field">
              <select aria-label="Prerequisite flag" value={p.flagKey} disabled={readOnly}
                      onChange={(e) => update({ prerequisites: draft.prerequisites.map((x, j) => (j === i ? { flagKey: e.target.value, variation: 0 } : x)) })}>
                {otherFlags.map((f) => <option key={f.key} value={f.key}>{f.name}</option>)}
              </select>
              must serve
              <select aria-label="Required variation" value={p.variation} disabled={readOnly}
                      onChange={(e) => update({ prerequisites: draft.prerequisites.map((x, j) => (j === i ? { ...x, variation: Number(e.target.value) } : x)) })}>
                {(prereqFlag?.variations ?? []).map((_, vi) => <option key={vi} value={vi}>{variationLabel(prereqFlag!.variations, vi)}</option>)}
              </select>
              {!readOnly && <button className="icon-btn" aria-label="Remove prerequisite"
                                    onClick={() => update({ prerequisites: draft.prerequisites.filter((_, j) => j !== i) })}>×</button>}
            </div>
          );
        })}
      </section>

      <section className={`card section ${enabled ? '' : 'dimmed'}`}>
        <h3>Individual targets</h3>
        <p className="muted">Specific context keys that always receive a variation. Checked before rules.</p>
        {variations.map((_, i) => (
          <div key={i} className="target-row">
            <span className="target-label">{variationLabel(variations, i)}</span>
            {readOnly ? <span className="muted">{targetsFor(i).join(', ') || '—'}</span> : (
              <ValuesInput ariaLabel={`Context keys receiving ${variationLabel(variations, i)}`} values={targetsFor(i)}
                           onChange={(v) => setTargets(i, v)} placeholder="user key…" />
            )}
          </div>
        ))}
      </section>

      <section className={`card section ${enabled ? '' : 'dimmed'}`}>
        <div className="section-head">
          <div>
            <h3>Rules</h3>
            <p className="muted">Evaluated top to bottom; the first rule whose conditions all match wins.</p>
          </div>
          {!readOnly && (
            <button className="btn btn-sm" onClick={() => update({ rules: [...draft.rules, { clauses: [newClause()], serve: { variation: 0 } }] })}>
              + Rule
            </button>
          )}
        </div>
        {draft.rules.map((rule, i) => (
          <div key={rule.id ?? `new-${i}`} className="rule">
            <div className="rule-head">
              <span className="rule-index">{i + 1}</span>
              <input className="rule-desc" aria-label={`Rule ${i + 1} description`} placeholder="Describe this rule…"
                     value={rule.description ?? ''} disabled={readOnly}
                     onChange={(e) => updateRule(i, { ...rule, description: e.target.value })} />
              {!readOnly && (
                <div className="rule-actions">
                  <button className="icon-btn" aria-label="Move rule up" disabled={i === 0} onClick={() => moveRule(i, -1)}>↑</button>
                  <button className="icon-btn" aria-label="Move rule down" disabled={i === draft.rules.length - 1} onClick={() => moveRule(i, 1)}>↓</button>
                  <button className="icon-btn" aria-label="Delete rule" onClick={() => update({ rules: draft.rules.filter((_, j) => j !== i) })}>×</button>
                </div>
              )}
            </div>
            {rule.clauses.map((clause, j) => (
              <ClauseEditor key={j} index={j} clause={clause} segmentKeys={segmentKeys} disabled={readOnly}
                            onChange={(c) => updateRule(i, { ...rule, clauses: rule.clauses.map((x, k) => (k === j ? c : x)) })}
                            onRemove={() => updateRule(i, { ...rule, clauses: rule.clauses.filter((_, k) => k !== j) })} />
            ))}
            {!readOnly && (
              <button className="btn btn-ghost btn-sm" onClick={() => updateRule(i, { ...rule, clauses: [...rule.clauses, newClause()] })}>
                + Condition
              </button>
            )}
            <div className="rule-serve">
              <span>then</span>
              <ServeEditor label={`Rule ${i + 1} serve`} value={rule.serve} variations={variations} disabled={readOnly}
                           onChange={(serve) => updateRule(i, { ...rule, serve })} />
            </div>
          </div>
        ))}
      </section>

      <section className={`card section ${enabled ? '' : 'dimmed'}`}>
        <h3>Default rule</h3>
        <p className="muted">Served when nothing above matches.</p>
        <ServeEditor label="Default rule serve" value={draft.fallthrough} variations={variations} disabled={readOnly}
                     onChange={(fallthrough) => update({ fallthrough })} />
      </section>

      {!readOnly && (dirty || error != null) && (
        <div className="save-bar" role="region" aria-label="Unsaved changes">
          {conflict ? (
            <div className="banner banner-warning">
              <strong>Someone else changed this flag.</strong> {(error as ApiError).message}
              <button className="btn btn-sm" onClick={onReload}>Load latest</button>
            </div>
          ) : (
            <ErrorBanner error={error} />
          )}
          {problems.length > 0 && <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
          <div className="save-actions">
            <input aria-label="Change comment" placeholder="Comment for the audit log (optional)" value={comment}
                   onChange={(e) => setComment(e.target.value)} />
            <button className="btn" onClick={() => { setDraft(config.targeting); setEnabled(config.enabled); }} disabled={!dirty}>
              Discard
            </button>
            <button className="btn btn-primary" disabled={!dirty || saving || problems.length > 0}
                    onClick={() => onSave({ targeting: draft, enabled, version: config.version, comment: comment || undefined })}>
              {saving ? 'Saving…' : `Save to ${envName}`}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
