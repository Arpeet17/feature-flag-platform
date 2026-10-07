import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useEvaluate, useFlags, useProject } from '../api/hooks';
import type { EvaluationReason, FlagSummary } from '../api/types';
import { Badge, EnvDot, ErrorBanner } from '../components/ui';
import { useSelectedEnv } from '../components/useSelectedEnv';
import { formatValue, variationLabel } from '../lib/targeting';

const SAMPLE = `{
  "key": "user-1",
  "attributes": {
    "email": "jane@acme.com",
    "country": "IN",
    "plan": "pro",
    "appVersion": "2.4.0"
  }
}`;

export function describeReason(reason: EvaluationReason): string {
  switch (reason.kind) {
    case 'OFF': return 'Flag is off';
    case 'TARGET_MATCH': return 'Individually targeted';
    case 'RULE_MATCH': return `Matched rule ${(reason.ruleIndex ?? 0) + 1}${reason.inRollout ? ' (rollout)' : ''}`;
    case 'FALLTHROUGH': return `Default rule${reason.inRollout ? ' (rollout)' : ''}`;
    case 'PREREQUISITE_FAILED': return `Prerequisite “${reason.prerequisiteKey}” not met`;
    case 'ERROR': return `Error: ${reason.errorKind?.toLowerCase().replace(/_/g, ' ')}`;
  }
}

/** Answers "what would this user get, and why?" without deploying anything. */
export function DebuggerPage() {
  const { projectKey = '' } = useParams();
  const { data: project } = useProject(projectKey);
  const { data: flags } = useFlags(projectKey, {});
  const [env, selectEnv] = useSelectedEnv(projectKey, project?.environments);
  const evaluate = useEvaluate(projectKey);
  const [raw, setRaw] = useState(SAMPLE);
  const [parseError, setParseError] = useState<string | null>(null);

  const run = () => {
    try {
      const context = JSON.parse(raw);
      if (typeof context?.key !== 'string' || !context.key) throw new Error('"key" (string) is required');
      setParseError(null);
      if (env) evaluate.mutate({ envKey: env.key, context });
    } catch (e) {
      setParseError(e instanceof Error ? e.message : String(e));
    }
  };

  const byKey = new Map<string, FlagSummary>((flags?.items ?? []).map((f) => [f.key, f]));

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Evaluation debugger</h1>
          <p className="muted">See exactly what a user would get in an environment, and why.</p>
        </div>
      </header>
      <div className="debugger">
        <div className="card section">
          <div className="env-tabs" role="tablist" aria-label="Environment">
            {project?.environments.map((e) => (
              <button key={e.key} role="tab" aria-selected={env?.key === e.key}
                      className={`env-tab ${env?.key === e.key ? 'active' : ''}`} onClick={() => selectEnv(e.key)}>
                <EnvDot color={e.color} /> {e.name}
              </button>
            ))}
          </div>
          <label className="field">
            <span className="field-label">Context (JSON)</span>
            <textarea className="mono" rows={12} value={raw} onChange={(e) => setRaw(e.target.value)} spellCheck={false} />
          </label>
          {parseError && <div className="field-error">{parseError}</div>}
          <button className="btn btn-primary" onClick={run} disabled={evaluate.isPending}>Evaluate all flags</button>
        </div>
        <div className="card section">
          <ErrorBanner error={evaluate.error} />
          {!evaluate.data && <p className="muted">Results appear here.</p>}
          {evaluate.data && (
            <table className="table">
              <thead><tr><th>Flag</th><th>Value</th><th>Reason</th></tr></thead>
              <tbody>
                {Object.entries(evaluate.data.flags).map(([key, result]) => {
                  const flag = byKey.get(key);
                  return (
                    <tr key={key}>
                      <td><Link to={`/projects/${projectKey}/flags/${key}?env=${env?.key}`}>{flag?.name ?? key}</Link><div className="tiny muted">{key}</div></td>
                      <td>
                        <code className="value">{formatValue(result.value)}</code>
                        {flag && result.variation != null && <div className="tiny muted">{variationLabel(flag.variations, result.variation)}</div>}
                      </td>
                      <td><Badge tone={result.reason.kind === 'ERROR' ? 'danger' : result.reason.kind === 'OFF' ? 'neutral' : 'info'}>
                        {describeReason(result.reason)}</Badge></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}
