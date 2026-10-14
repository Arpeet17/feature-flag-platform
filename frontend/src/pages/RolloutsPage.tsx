import { Link, useParams } from 'react-router-dom';
import { useRollout, useRolloutAction, useRollouts } from '../api/hooks';
import type { GuardedRollout, RolloutStatus } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { RolloutProgress } from '../components/RolloutProgress';
import { Badge, EmptyState, ErrorBanner, Spinner } from '../components/ui';
import { formatDateTime, timeAgo } from '../lib/format';

export const STATUS: Record<RolloutStatus, { label: string; tone: 'info' | 'warning' | 'success' | 'danger' | 'neutral'; icon: string }> = {
  RUNNING: { label: 'Running', tone: 'info', icon: '▶' },
  PAUSED: { label: 'Paused', tone: 'warning', icon: '❚❚' },
  COMPLETED: { label: 'Completed', tone: 'success', icon: '✓' },
  ROLLED_BACK: { label: 'Rolled back', tone: 'danger', icon: '↺' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral', icon: '✕' },
};

export function StatusBadge({ status }: { status: RolloutStatus }) {
  const s = STATUS[status];
  return <Badge tone={s.tone}><span aria-hidden>{s.icon}</span> {s.label}</Badge>;
}

export function RolloutsPage() {
  const { projectKey = '' } = useParams();
  const { data, isLoading, error } = useRollouts(projectKey);
  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Guarded rollouts</h1>
          <p className="muted">Ramp a variation step by step while a guardrail metric is watched; regressions roll back automatically.</p>
        </div>
      </header>
      <ErrorBanner error={error} />
      {isLoading && <Spinner />}
      {data?.length === 0 && <EmptyState title="No rollouts yet">Start one from a flag's page: <em>Start guarded rollout</em>.</EmptyState>}
      <div className="rollout-list">
        {data?.map((r) => (
          <Link key={r.id} to={`/projects/${projectKey}/rollouts/${r.id}`} className="card card-link rollout-card">
            <div className="section-head">
              <div>
                <h3><code>{r.flagKey}</code> → {r.treatmentLabel}</h3>
                <span className="muted tiny">{r.environmentName} · started {timeAgo(r.createdAt)}{r.guardrailEventKey ? ` · guardrail “${r.guardrailEventKey}”` : ''}</span>
              </div>
              <StatusBadge status={r.status} />
            </div>
            <RolloutProgress rollout={r} />
            {r.lastDecision && <p className="tiny muted">{r.lastDecision}</p>}
          </Link>
        ))}
      </div>
    </div>
  );
}

export function RolloutDetailPage() {
  const { projectKey = '', rolloutId = '0' } = useParams();
  const id = Number(rolloutId);
  const { data: r, isLoading, error } = useRollout(projectKey, id);
  const action = useRolloutAction(projectKey, id);
  const { can } = useAuth();

  if (isLoading) return <div className="page"><Spinner /></div>;
  if (error || !r) return <div className="page"><ErrorBanner error={error ?? new Error('Rollout not found')} /></div>;

  return (
    <div className="page">
      <nav className="breadcrumbs" aria-label="Breadcrumb">
        <Link to={`/projects/${projectKey}/rollouts`}>Rollouts</Link> / <span>#{r.id}</span>
      </nav>
      <header className="page-header">
        <div>
          <h1>Rolling out <Link to={`/projects/${projectKey}/flags/${r.flagKey}?env=${r.environmentKey}`}><code>{r.flagKey}</code></Link> → {r.treatmentLabel} <StatusBadge status={r.status} /></h1>
          <p className="muted">
            {r.environmentName} · control “{r.controlLabel}” · steps every {formatInterval(r.stepIntervalSeconds)}
            {r.minUsersPerStep > 0 && ` and ≥ ${r.minUsersPerStep} treatment users`}
            {r.guardrailEventKey ? <> · guardrail <code>{r.guardrailEventKey}</code> must not get significantly worse</> : ' · no guardrail (time-based)'}
          </p>
        </div>
        {can('EDITOR') && (
          <div className="header-actions">
            {r.status === 'RUNNING' && <button className="btn" onClick={() => action.mutate('pause')}>Pause</button>}
            {r.status === 'PAUSED' && <button className="btn btn-primary" onClick={() => action.mutate('resume')}>Resume</button>}
            {(r.status === 'RUNNING' || r.status === 'PAUSED') && (
              <button className="btn btn-danger" onClick={() => window.confirm('Stop automation? The flag stays exactly as it is now.') && action.mutate('cancel')}>
                Cancel
              </button>
            )}
          </div>
        )}
      </header>
      <ErrorBanner error={action.error} />
      {r.status === 'ROLLED_BACK' && (
        <div className="banner banner-error" role="alert"><strong>↺ Automatically rolled back.</strong> {r.lastDecision}</div>
      )}

      <section className="card section">
        <div className="section-head">
          <h3>{r.currentPercentage}% of traffic on {r.treatmentLabel}</h3>
          {(r.status === 'RUNNING') && <span className="tiny muted">step {r.currentStep + 1}/{r.steps.length} · next check {timeAgo(r.nextCheckAt)}</span>}
        </div>
        <RolloutProgress rollout={r} />
        {r.lastDecision && r.status !== 'ROLLED_BACK' && <p className="muted">{r.lastDecision}</p>}
      </section>

      {r.guardrail && <GuardrailPanel rollout={r} />}

      <section className="card section">
        <h3>Timeline</h3>
        <ol className="timeline">
          {r.history?.map((h, i) => (
            <li key={i} className={`tl-${h.action.toLowerCase()}`}>
              <div className="tl-head">
                <strong>{h.action.replace('_', ' ').toLowerCase()}</strong> <span className="muted">at {h.percentage}%</span>
                <time className="tiny muted" dateTime={h.at} title={formatDateTime(h.at)}>{timeAgo(h.at)}</time>
              </div>
              <div className="tiny">{h.detail}</div>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}

function GuardrailPanel({ rollout: r }: { rollout: GuardedRollout }) {
  const a = r.guardrail!;
  const treatment = a.arms.find((x) => x.variation === r.treatmentVariation);
  const control = a.arms.find((x) => x.variation === r.controlVariation);
  const c = a.comparisons[0];
  const pct = (v?: number) => (v === undefined ? '—' : `${(v * 100).toFixed(2)}%`);
  return (
    <section className="card section">
      <div className="section-head">
        <h3>Guardrail “{r.guardrailEventKey}” this step</h3>
        <span className="tiny muted">users first exposed since {timeAgo(r.stepStartedAt)} · lower is better</span>
      </div>
      <div className="guardrail-grid">
        <div><div className="tiny muted">{r.treatmentLabel} (treatment)</div><div className="big">{pct(treatment?.estimate)}</div>
          <div className="tiny muted">{treatment?.users.toLocaleString() ?? 0} users · CI {pct(treatment?.ciLow)}–{pct(treatment?.ciHigh)}</div></div>
        <div><div className="tiny muted">{r.controlLabel} (control)</div><div className="big">{pct(control?.estimate)}</div>
          <div className="tiny muted">{control?.users.toLocaleString() ?? 0} users · CI {pct(control?.ciLow)}–{pct(control?.ciHigh)}</div></div>
        <div><div className="tiny muted">Status</div>
          <div className="big">{c?.verdict === 'WORSE' ? '▼ Worse' : c?.verdict === 'INSUFFICIENT_DATA' ? '○ Gathering data' : '● Healthy'}</div>
          <div className="tiny muted">{c && c.verdict !== 'INSUFFICIENT_DATA' ? `p = ${c.pValue.toFixed(3)}` : 'needs ≥ 100 users and 5 events per arm'}</div></div>
      </div>
      {a.sampleRatio.mismatch && <div className="banner banner-error">Sample ratio mismatch: traffic isn't split as configured.</div>}
    </section>
  );
}

export function formatInterval(seconds: number): string {
  if (seconds % 3600 === 0) return `${seconds / 3600}h`;
  if (seconds % 60 === 0) return `${seconds / 60}m`;
  return `${seconds}s`;
}
