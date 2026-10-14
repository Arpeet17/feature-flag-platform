import { Link, useParams } from 'react-router-dom';
import { useExperiment, useStopExperiment } from '../api/hooks';
import type { Experiment } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { LiftChart } from '../components/LiftChart';
import { Badge, ErrorBanner, Spinner } from '../components/ui';
import { formatEstimate, formatLift, formatP, formatRelative, verdictFor } from '../lib/experiments';
import { formatDateTime, timeAgo } from '../lib/format';

export function ExperimentDetailPage() {
  const { projectKey = '', experimentKey = '' } = useParams();
  const { data: e, isLoading, error } = useExperiment(projectKey, experimentKey);
  const stop = useStopExperiment(projectKey, experimentKey);
  const { can } = useAuth();

  if (isLoading) return <div className="page"><Spinner /></div>;
  if (error || !e) return <div className="page"><ErrorBanner error={error ?? new Error('Experiment not found')} /></div>;

  return (
    <div className="page">
      <nav className="breadcrumbs" aria-label="Breadcrumb">
        <Link to={`/projects/${projectKey}/experiments`}>Experiments</Link> / <span>{e.key}</span>
      </nav>
      <header className="page-header">
        <div>
          <h1>{e.name} <Badge tone={e.status === 'RUNNING' ? 'info' : 'neutral'}>{e.status.toLowerCase()}</Badge></h1>
          {e.hypothesis && <p className="muted">{e.hypothesis}</p>}
          <div className="flag-meta">
            <span>Flag <Link to={`/projects/${projectKey}/flags/${e.flagKey}?env=${e.environmentKey}`}><code>{e.flagKey}</code></Link></span>
            <span>· {e.environmentName}</span>
            <span>· metric <code>{e.metricEventKey}</code> ({e.metricType === 'CONVERSION' ? 'conversion rate' : 'mean per user'}, {e.higherIsBetter ? 'higher' : 'lower'} is better)</span>
            <span className="muted">· started {timeAgo(e.startedAt)}{e.stoppedAt ? `, stopped ${timeAgo(e.stoppedAt)}` : ''}</span>
          </div>
        </div>
        {can('EDITOR') && e.status === 'RUNNING' && (
          <button className="btn" disabled={stop.isPending}
                  onClick={() => window.confirm('Stop the experiment? Results freeze at this moment.') && stop.mutate()}>
            Stop experiment
          </button>
        )}
      </header>
      <ErrorBanner error={stop.error} />
      {e.results && <Results experiment={e} />}
    </div>
  );
}

function Results({ experiment: e }: { experiment: Experiment }) {
  const { analysis, excludedNonRandomized, from, to } = e.results!;
  const type = analysis.metricType;
  const control = analysis.arms.find((a) => a.variation === analysis.controlVariation);
  const srm = analysis.sampleRatio;

  return (
    <>
      {srm.mismatch && (
        <div className="banner banner-error srm" role="alert">
          <strong>⚠ Sample ratio mismatch: don't trust these results.</strong>
          <span>
            Users are not split as configured (χ² p = {srm.pValue?.toExponential(2)}). Usually exposures are being lost or
            duplicated for one variation, for example the new code path fails before it logs. Fix the cause and restart the
            experiment; any lift below is likely an artefact.
          </span>
        </div>
      )}
      {analysis.warnings.filter((w) => !w.startsWith('Sample ratio mismatch')).map((w) => (
        <div key={w} className="banner banner-warning">{w}</div>
      ))}

      <section className="card section">
        <div className="section-head">
          <h3>Lift vs. {control?.label ?? 'control'}</h3>
          <span className="muted tiny">{Math.round((1 - analysis.adjustedAlpha) * 1000) / 10}% confidence intervals · hover for details</span>
        </div>
        <LiftChart comparisons={analysis.comparisons} metricType={type} untrusted={srm.mismatch} />
      </section>

      <section className="card section">
        <h3>Variations</h3>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Variation</th><th className="num">Users</th>
                <th className="num">{type === 'CONVERSION' ? 'Conversions' : 'Users with events'}</th>
                <th className="num">{type === 'CONVERSION' ? 'Conversion rate' : 'Mean per user'}</th>
                <th className="num">95% CI</th><th className="num">Lift</th><th className="num">Relative</th>
                <th className="num">p-value</th><th>Verdict</th>
              </tr>
            </thead>
            <tbody>
              {[...analysis.arms].sort((a, b) => Number(b.variation === analysis.controlVariation) - Number(a.variation === analysis.controlVariation) || a.variation - b.variation).map((arm) => {
                const cmp = analysis.comparisons.find((c) => c.variation === arm.variation);
                const v = cmp ? verdictFor(cmp.verdict, srm.mismatch) : null;
                return (
                  <tr key={arm.variation}>
                    <td>{arm.label}{arm.variation === analysis.controlVariation && <Badge>control</Badge>}</td>
                    <td className="num">{arm.users.toLocaleString()}</td>
                    <td className="num">{arm.conversions.toLocaleString()}</td>
                    <td className="num">{formatEstimate(arm.estimate, type)}</td>
                    <td className="num muted">{formatEstimate(arm.ciLow, type)} – {formatEstimate(arm.ciHigh, type)}</td>
                    <td className="num">{cmp && cmp.verdict !== 'INSUFFICIENT_DATA' ? formatLift(cmp.absoluteLift, type) : '—'}</td>
                    <td className="num">{cmp && cmp.verdict !== 'INSUFFICIENT_DATA' ? formatRelative(cmp.relativeLift) : '—'}</td>
                    <td className="num">{cmp && cmp.verdict !== 'INSUFFICIENT_DATA' ? formatP(cmp.pValue) : '—'}</td>
                    <td>
                      {v && <Badge tone={v.tone === 'neutral' ? 'neutral' : v.tone}><span aria-hidden>{v.icon}</span> {v.label}</Badge>}
                      {cmp?.usersNeededPerArm && (
                        <div className="tiny muted">≈{cmp.usersNeededPerArm.toLocaleString()} users/arm to detect this effect</div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card section tiny muted">
        <div>
          Window {formatDateTime(from)} – {formatDateTime(to)}. Only users randomly assigned by a percentage rollout are analysed
          (first exposure wins); {excludedNonRandomized.toLocaleString()} rule-targeted exposure{excludedNonRandomized === 1 ? ' was' : 's were'} excluded.
          {srm.checked && srm.expectedShare && (
            <> Configured split: {Object.entries(srm.expectedShare).map(([k, s]) =>
              `${analysis.arms.find((a) => String(a.variation) === k)?.label ?? k} ${(s * 100).toFixed(1)}%`).join(' / ')};
              observed: {Object.entries(srm.observedShare).map(([k, s]) =>
                `${analysis.arms.find((a) => String(a.variation) === k)?.label ?? k} ${(s * 100).toFixed(1)}%`).join(' / ')}.</>
          )}
          {analysis.comparisons.length > 1 && <> Each treatment is tested at α = {analysis.adjustedAlpha} (Bonferroni).</>}
        </div>
      </section>
    </>
  );
}
