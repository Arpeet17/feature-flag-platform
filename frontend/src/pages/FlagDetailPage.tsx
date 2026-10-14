import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  useCopyTargeting, useFlag, useFlagAction, useFlags, useProject, useRollouts, useSegments, useUpdateFlag, useUpdateTargeting,
} from '../api/hooks';
import type { Flag, FlagConfig } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Badge, CopyButton, EnvDot, ErrorBanner, Field, Modal, Spinner, ValuesInput } from '../components/ui';
import { StartRolloutModal } from '../components/StartRolloutModal';
import { useSelectedEnv } from '../components/useSelectedEnv';
import { formatDateTime, timeAgo } from '../lib/format';
import { parseVariationValue, stringifyVariationValue, variationLabel } from '../lib/targeting';
import { AuditList } from './AuditPage';
import { LifecycleBadge } from './FlagsPage';
import { TargetingEditor } from './targeting/TargetingEditor';

type Tab = 'targeting' | 'settings' | 'history';

export function FlagDetailPage() {
  const { projectKey = '', flagKey = '' } = useParams();
  const { data: project } = useProject(projectKey);
  const { data: flag, isLoading, error, refetch } = useFlag(projectKey, flagKey);
  const { data: segments } = useSegments(projectKey);
  const { data: allFlags } = useFlags(projectKey, {});
  const { can } = useAuth();
  const [env, selectEnv] = useSelectedEnv(projectKey, project?.environments);
  const [tab, setTab] = useState<Tab>('targeting');
  const [copying, setCopying] = useState(false);
  const [rollingOut, setRollingOut] = useState(false);
  const { data: rollouts } = useRollouts(projectKey);
  const updateTargeting = useUpdateTargeting(projectKey, flagKey);
  const action = useFlagAction(projectKey, flagKey);
  const navigate = useNavigate();

  if (isLoading) return <div className="page"><Spinner /></div>;
  if (error || !flag) return <div className="page"><ErrorBanner error={error ?? new Error('Flag not found')} /></div>;

  const config = flag.environments.find((c) => c.environmentKey === env?.key);
  const activeRollout = rollouts?.find((r) => r.flagKey === flag.key && r.environmentKey === env?.key
    && (r.status === 'RUNNING' || r.status === 'PAUSED'));
  const otherFlags = (allFlags?.items ?? []).filter((f) => f.key !== flag.key);

  const runAction = (kind: 'archive' | 'restore' | 'delete') => {
    const verbs = { archive: 'Archive', restore: 'Restore', delete: 'Permanently delete' };
    if (!window.confirm(`${verbs[kind]} "${flag.name}"?`)) return;
    action.mutate(kind, { onSuccess: () => kind === 'delete' && navigate(`/projects/${projectKey}/flags`) });
  };

  return (
    <div className="page">
      <nav className="breadcrumbs" aria-label="Breadcrumb">
        <Link to={`/projects/${projectKey}/flags`}>Flags</Link> / <span>{flag.key}</span>
      </nav>
      <header className="page-header">
        <div>
          <h1>{flag.name}</h1>
          <div className="flag-meta">
            <code>{flag.key}</code> <CopyButton text={flag.key} label="Copy key" />
            <Badge>{flag.type.toLowerCase()}</Badge>
            <LifecycleBadge lifecycle={flag.lifecycle} />
            {flag.tags.map((t) => <Badge key={t}>#{t}</Badge>)}
          </div>
          {flag.description && <p className="muted">{flag.description}</p>}
        </div>
        {can('EDITOR') && (
          <div className="header-actions">
            {flag.archived ? (
              <>
                <button className="btn" onClick={() => runAction('restore')}>Restore</button>
                {can('ADMIN') && <button className="btn btn-danger" onClick={() => runAction('delete')}>Delete</button>}
              </>
            ) : (
              <button className="btn" onClick={() => runAction('archive')} disabled={flag.dependentFlags.length > 0}
                      title={flag.dependentFlags.length ? `Prerequisite of ${flag.dependentFlags.join(', ')}` : undefined}>
                Archive
              </button>
            )}
          </div>
        )}
      </header>
      <ErrorBanner error={action.error} />
      {flag.archived && <div className="banner banner-warning">This flag is archived. SDKs receive their fallback value.</div>}

      <div className="tabs" role="tablist">
        {(['targeting', 'settings', 'history'] as Tab[]).map((t) => (
          <button key={t} role="tab" aria-selected={tab === t} className={`tab ${tab === t ? 'active' : ''}`} onClick={() => setTab(t)}>
            {t === 'targeting' ? 'Targeting' : t === 'settings' ? 'Variations & settings' : 'History'}
          </button>
        ))}
      </div>

      {tab === 'targeting' && (
        <>
          <div className="toolbar">
            <div className="env-tabs" role="tablist" aria-label="Environment">
              {project?.environments.map((e) => {
                const c = flag.environments.find((x) => x.environmentKey === e.key);
                return (
                  <button key={e.key} role="tab" aria-selected={env?.key === e.key}
                          className={`env-tab ${env?.key === e.key ? 'active' : ''}`} onClick={() => selectEnv(e.key)}>
                    <EnvDot color={e.color} /> {e.name} <Badge tone={c?.enabled ? 'success' : 'neutral'}>{c?.enabled ? 'on' : 'off'}</Badge>
                  </button>
                );
              })}
            </div>
            {can('EDITOR') && env && (project?.environments.length ?? 0) > 1 && (
              <button className="btn btn-sm" onClick={() => setCopying(true)}>Copy from environment…</button>
            )}
            {can('EDITOR') && env && !activeRollout && !flag.archived && (
              <button className="btn btn-sm" onClick={() => setRollingOut(true)}>Start guarded rollout…</button>
            )}
          </div>
          {activeRollout && (
            <div className="banner banner-warning">
              <strong>⇡ Guarded rollout {activeRollout.status === 'PAUSED' ? 'paused' : 'in progress'}</strong> at {activeRollout.currentPercentage}%:
              the default rule is managed automatically; editing it pauses the rollout.
              <Link to={`/projects/${projectKey}/rollouts/${activeRollout.id}`}>View rollout →</Link>
            </div>
          )}
          {rollingOut && env && (
            <StartRolloutModal projectKey={projectKey} flag={flag} envKey={env.key} envName={env.name} onClose={() => setRollingOut(false)} />
          )}
          {config && env && (
            <div className="detail-grid">
              <TargetingEditor
                flag={flag} config={config} envName={env.name}
                segmentKeys={(segments ?? []).map((s) => s.key)} otherFlags={otherFlags}
                canEdit={can('EDITOR') && !flag.archived}
                saving={updateTargeting.isPending} error={updateTargeting.error}
                onSave={(req) => updateTargeting.mutate({ envKey: env.key, ...req })}
                onReload={() => { updateTargeting.reset(); refetch(); }}
              />
              <aside className="side">
                <UsagePanel flag={flag} config={config} />
                <SnippetPanel flag={flag} />
                <div className="card section tiny muted">
                  Last changed {timeAgo(config.updatedAt)} by {config.updatedBy ?? 'unknown'} · version {config.version}
                </div>
              </aside>
            </div>
          )}
          {copying && env && (
            <CopyTargetingModal flag={flag} projectKey={projectKey} target={config!} environments={project?.environments ?? []}
                                onClose={() => setCopying(false)} />
          )}
        </>
      )}

      {tab === 'settings' && <SettingsTab flag={flag} projectKey={projectKey} canEdit={can('EDITOR') && !flag.archived} />}
      {tab === 'history' && <AuditList projectKey={projectKey} resourceKey={flag.key} />}
    </div>
  );
}

function UsagePanel({ flag, config }: { flag: Flag; config: FlagConfig }) {
  const total = config.usage.total;
  return (
    <div className="card section">
      <h4>Evaluations in {config.environmentName}</h4>
      {total === 0 ? (
        <p className="muted tiny">No evaluations recorded since the server started.</p>
      ) : (
        <>
          <ul className="usage">
            {Object.entries(config.usage.byVariation).map(([idx, count]) => (
              <li key={idx}>
                <span>{Number(idx) < 0 ? 'errors' : variationLabel(flag.variations, Number(idx))}</span>
                <span className="usage-bar"><span style={{ width: `${(count / total) * 100}%` }} /></span>
                <span className="tiny">{count.toLocaleString()}</span>
              </li>
            ))}
          </ul>
          <p className="tiny muted">Last evaluated {timeAgo(config.usage.lastEvaluatedAt)}</p>
        </>
      )}
    </div>
  );
}

function SnippetPanel({ flag }: { flag: Flag }) {
  const fallback = stringifyVariationValue(flag.variations[flag.defaultOffVariation]?.value ?? false);
  const method = { BOOLEAN: 'boolVariation', STRING: 'stringVariation', NUMBER: 'numberVariation', JSON: 'jsonVariation' }[flag.type];
  const literal = flag.type === 'STRING' ? `'${fallback}'` : fallback.replace(/\s+/g, ' ');
  const code = `const value = client.${method}('${flag.key}', ${literal});`;
  return (
    <div className="card section">
      <h4>Use in code</h4>
      <pre className="code">{code}</pre>
      <CopyButton text={code} />
    </div>
  );
}

function CopyTargetingModal({ flag, projectKey, target, environments, onClose }: {
  flag: Flag;
  projectKey: string;
  target: FlagConfig;
  environments: { key: string; name: string }[];
  onClose: () => void;
}) {
  const copy = useCopyTargeting(projectKey, flag.key);
  const sources = environments.filter((e) => e.key !== target.environmentKey);
  const [source, setSource] = useState(sources[0]?.key ?? '');
  const [includeEnabled, setIncludeEnabled] = useState(false);
  const sourceConfig = flag.environments.find((c) => c.environmentKey === source);

  return (
    <Modal title={`Copy targeting into ${target.environmentName}`} onClose={onClose} footer={
      <>
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={!source || copy.isPending}
                onClick={() => copy.mutate({ targetEnv: target.environmentKey, sourceEnvironment: source, includeEnabledState: includeEnabled,
                  targetVersion: target.version }, { onSuccess: onClose })}>
          Copy targeting
        </button>
      </>
    }>
      <ErrorBanner error={copy.error} />
      <p className="muted">Promote a configuration you've verified (e.g. in staging) without re-entering it by hand.</p>
      <Field label="Source environment">
        <select value={source} onChange={(e) => setSource(e.target.value)}>
          {sources.map((e) => <option key={e.key} value={e.key}>{e.name}</option>)}
        </select>
      </Field>
      {sourceConfig && (
        <p className="tiny muted">
          Source has {sourceConfig.targeting.rules.length} rule(s), {sourceConfig.targeting.targets.reduce((n, t) => n + t.values.length, 0)} target(s)
          and is {sourceConfig.enabled ? 'on' : 'off'}.
        </p>
      )}
      <label className="checkbox">
        <input type="checkbox" checked={includeEnabled} onChange={(e) => setIncludeEnabled(e.target.checked)} />
        Also copy the on/off state
      </label>
    </Modal>
  );
}

function SettingsTab({ flag, projectKey, canEdit }: { flag: Flag; projectKey: string; canEdit: boolean }) {
  const update = useUpdateFlag(projectKey, flag.key);
  const [name, setName] = useState(flag.name);
  const [description, setDescription] = useState(flag.description ?? '');
  const [tags, setTags] = useState(flag.tags);
  const [temporary, setTemporary] = useState(flag.temporary);
  const [variationNames, setVariationNames] = useState(flag.variations.map((v) => v.name ?? ''));
  const [rawValues, setRawValues] = useState(flag.variations.map((v) => stringifyVariationValue(v.value)));
  const parsed = rawValues.map((r) => parseVariationValue(flag.type, r));
  const invalid = parsed.some((p) => p.error);

  const save = () => update.mutate({
    name, description, tags, temporary,
    variations: parsed.map((p, i) => ({ value: p.value!, name: variationNames[i] || null, description: flag.variations[i]?.description })),
  });

  return (
    <div className="card section settings">
      <ErrorBanner error={update.error} />
      {update.isSuccess && <div className="banner banner-success">Saved.</div>}
      <fieldset disabled={!canEdit} className="plain">
        <div className="form-grid">
          <Field label="Name"><input value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label="Tags"><ValuesInput ariaLabel="Tags" values={tags} onChange={setTags} placeholder="Add tag…" /></Field>
        </div>
        <Field label="Description"><textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
        <label className="checkbox">
          <input type="checkbox" checked={temporary} onChange={(e) => setTemporary(e.target.checked)} /> Temporary flag
        </label>

        <h3>Variations</h3>
        <p className="muted tiny">Changing a value takes effect immediately in every environment where it is served.</p>
        {rawValues.map((raw, i) => (
          <div key={i} className="variation-row">
            <span className="variation-index">{i}</span>
            <input aria-label={`Variation ${i} name`} placeholder="Name" value={variationNames[i]}
                   onChange={(e) => setVariationNames(variationNames.map((n, j) => (j === i ? e.target.value : n)))} />
            {flag.type === 'JSON' ? (
              <textarea className="mono" rows={3} aria-label={`Variation ${i} value`} value={raw}
                        onChange={(e) => setRawValues(rawValues.map((r, j) => (j === i ? e.target.value : r)))} />
            ) : (
              <input className="mono" aria-label={`Variation ${i} value`} value={raw} disabled={flag.type === 'BOOLEAN'}
                     onChange={(e) => setRawValues(rawValues.map((r, j) => (j === i ? e.target.value : r)))} />
            )}
            {parsed[i].error && <span className="field-error">{parsed[i].error}</span>}
          </div>
        ))}
      </fieldset>
      {canEdit && (
        <div className="save-actions">
          <button className="btn btn-primary" disabled={invalid || update.isPending} onClick={save}>Save settings</button>
        </div>
      )}
      <p className="tiny muted">Created {formatDateTime(flag.createdAt)}{flag.createdBy ? ` by ${flag.createdBy}` : ''}.</p>
    </div>
  );
}
