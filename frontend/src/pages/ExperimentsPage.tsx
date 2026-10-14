import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useCreateExperiment, useExperiments, useFlags, useProject } from '../api/hooks';
import type { MetricType } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Badge, EmptyState, ErrorBanner, Field, Modal, Spinner } from '../components/ui';
import { KEY_PATTERN, slugify, timeAgo } from '../lib/format';
import { variationLabel } from '../lib/targeting';

export function ExperimentsPage() {
  const { projectKey = '' } = useParams();
  const { data: experiments, isLoading, error } = useExperiments(projectKey);
  const { can } = useAuth();
  const [creating, setCreating] = useState(false);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Experiments</h1>
          <p className="muted">A/B/n tests on flag variations, analysed with confidence intervals and sample-ratio checks.</p>
        </div>
        {can('EDITOR') && <button className="btn btn-primary" onClick={() => setCreating(true)}>New experiment</button>}
      </header>
      <ErrorBanner error={error} />
      {isLoading && <Spinner />}
      {experiments?.length === 0 && (
        <EmptyState title="No experiments yet">
          Split traffic with a percentage rollout, report events with <code>client.track()</code>, and compare variations here.
        </EmptyState>
      )}
      <div className="grid-cards">
        {experiments?.map((e) => (
          <Link key={e.key} to={`/projects/${projectKey}/experiments/${e.key}`} className="card card-link">
            <div className="section-head">
              <h3>{e.name}</h3>
              <Badge tone={e.status === 'RUNNING' ? 'info' : 'neutral'}>{e.status.toLowerCase()}</Badge>
            </div>
            {e.hypothesis && <p className="muted">{e.hypothesis}</p>}
            <div className="card-meta">
              <span>flag <code>{e.flagKey}</code> · metric <code>{e.metricEventKey}</code></span>
              <span className="muted">{e.environmentName} · started {timeAgo(e.startedAt)}</span>
            </div>
          </Link>
        ))}
      </div>
      {creating && <CreateExperimentModal projectKey={projectKey} onClose={() => setCreating(false)} />}
    </div>
  );
}

function CreateExperimentModal({ projectKey, onClose }: { projectKey: string; onClose: () => void }) {
  const create = useCreateExperiment(projectKey);
  const { data: project } = useProject(projectKey);
  const { data: flags } = useFlags(projectKey, {});
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [hypothesis, setHypothesis] = useState('');
  const [environment, setEnvironment] = useState('production');
  const [flagKey, setFlagKey] = useState('');
  const [metricEventKey, setMetricEventKey] = useState('');
  const [metricType, setMetricType] = useState<MetricType>('CONVERSION');
  const [higherIsBetter, setHigherIsBetter] = useState(true);
  const [controlVariation, setControlVariation] = useState(0);
  const flag = flags?.items.find((f) => f.key === flagKey);
  const key = slugify(name);
  const valid = name && KEY_PATTERN.test(key) && flagKey && KEY_PATTERN.test(metricEventKey);

  return (
    <Modal title="New experiment" wide onClose={onClose} footer={
      <>
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={!valid || create.isPending}
                onClick={() => create.mutate({ key, name, hypothesis, environment, flagKey, metricEventKey, metricType, higherIsBetter, controlVariation },
                  { onSuccess: (e) => navigate(`/projects/${projectKey}/experiments/${e.key}`) })}>
          Start experiment
        </button>
      </>
    }>
      <ErrorBanner error={create.error} />
      <div className="form-grid">
        <Field label="Name" hint={key && <>Key: <code>{key}</code></>}><input autoFocus value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Environment">
          <select value={environment} onChange={(e) => setEnvironment(e.target.value)}>
            {project?.environments.map((e) => <option key={e.key} value={e.key}>{e.name}</option>)}
          </select>
        </Field>
      </div>
      <Field label="Hypothesis"><textarea rows={2} value={hypothesis} onChange={(e) => setHypothesis(e.target.value)}
                                          placeholder="Changing X will increase Y because Z" /></Field>
      <div className="form-grid">
        <Field label="Flag" hint="Its percentage rollout defines the traffic split; only randomly assigned users are analysed.">
          <select value={flagKey} onChange={(e) => { setFlagKey(e.target.value); setControlVariation(0); }}>
            <option value="">Select a flag…</option>
            {flags?.items.map((f) => <option key={f.key} value={f.key}>{f.name}</option>)}
          </select>
        </Field>
        <Field label="Control variation">
          <select value={controlVariation} onChange={(e) => setControlVariation(Number(e.target.value))} disabled={!flag}>
            {flag?.variations.map((_, i) => <option key={i} value={i}>{variationLabel(flag.variations, i)}</option>)}
          </select>
        </Field>
      </div>
      <div className="form-grid">
        <Field label="Metric event" hint={<>Sent from your app with <code>track('{metricEventKey || 'purchase'}')</code></>}>
          <input value={metricEventKey} onChange={(e) => setMetricEventKey(e.target.value)} placeholder="purchase" />
        </Field>
        <Field label="Metric type">
          <select value={metricType} onChange={(e) => setMetricType(e.target.value as MetricType)}>
            <option value="CONVERSION">Conversion rate (did the user do it?)</option>
            <option value="MEAN">Mean value per user (e.g. revenue)</option>
          </select>
        </Field>
      </div>
      <label className="checkbox">
        <input type="checkbox" checked={higherIsBetter} onChange={(e) => setHigherIsBetter(e.target.checked)} /> Higher is better
      </label>
    </Modal>
  );
}
