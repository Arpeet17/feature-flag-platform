import { useDeferredValue, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useCreateFlag, useFlags, useProject, useTags, useToggleFlag, type CreateFlagBody } from '../api/hooks';
import type { FlagLifecycle, FlagSummary, FlagType, Variation } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Badge, EmptyState, EnvDot, ErrorBanner, Field, Modal, Spinner, Toggle, ValuesInput } from '../components/ui';
import { useSelectedEnv } from '../components/useSelectedEnv';
import { KEY_PATTERN, slugify, timeAgo } from '../lib/format';
import { formatValue, parseVariationValue } from '../lib/targeting';

const LIFECYCLE: Record<FlagLifecycle, { label: string; tone: 'neutral' | 'success' | 'warning' | 'info' | 'accent'; title: string }> = {
  NEW: { label: 'New', tone: 'info', title: 'Created in the last 7 days' },
  ACTIVE: { label: 'Active', tone: 'success', title: 'Temporary flag in active use' },
  POTENTIALLY_STALE: { label: 'Stale?', tone: 'warning', title: 'Unchanged for 30+ days and serving a single value everywhere – consider removing it from code' },
  PERMANENT: { label: 'Permanent', tone: 'accent', title: 'Long-lived flag such as a kill switch or entitlement' },
  ARCHIVED: { label: 'Archived', tone: 'neutral', title: 'Archived' },
};

export function LifecycleBadge({ lifecycle }: { lifecycle: FlagLifecycle }) {
  const l = LIFECYCLE[lifecycle];
  return <Badge tone={l.tone} title={l.title}>{l.label}</Badge>;
}

export function FlagsPage() {
  const { projectKey = '' } = useParams();
  const { data: project } = useProject(projectKey);
  const { can } = useAuth();
  const [search, setSearch] = useState('');
  const [tag, setTag] = useState('');
  const [archived, setArchived] = useState(false);
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const deferredSearch = useDeferredValue(search);
  const { data, isLoading, error, isFetching } = useFlags(projectKey, { q: deferredSearch, tag, archived, page });
  const { data: tags } = useTags(projectKey);
  const [env, selectEnv] = useSelectedEnv(projectKey, project?.environments);
  const toggle = useToggleFlag(projectKey);

  const onToggle = (flag: FlagSummary, envKey: string, enabled: boolean) => {
    const envName = project?.environments.find((e) => e.key === envKey)?.name ?? envKey;
    if (envKey === 'production' && !window.confirm(`Turn ${flag.name} ${enabled ? 'ON' : 'OFF'} in ${envName}?`)) return;
    toggle.mutate({ flagKey: flag.key, envKey, enabled });
  };

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Feature flags</h1>
          <p className="muted">{project?.name}</p>
        </div>
        {can('EDITOR') && <button className="btn btn-primary" onClick={() => setCreating(true)}>New flag</button>}
      </header>

      <div className="toolbar">
        <input type="search" className="search" placeholder="Search by key or name…" aria-label="Search flags"
               value={search} onChange={(e) => { setSearch(e.target.value); setPage(0); }} />
        <select aria-label="Filter by tag" value={tag} onChange={(e) => { setTag(e.target.value); setPage(0); }}>
          <option value="">All tags</option>
          {tags?.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
        <label className="checkbox">
          <input type="checkbox" checked={archived} onChange={(e) => { setArchived(e.target.checked); setPage(0); }} /> Archived
        </label>
        <div className="env-tabs" role="tablist" aria-label="Environment">
          {project?.environments.map((e) => (
            <button key={e.key} role="tab" aria-selected={env?.key === e.key}
                    className={`env-tab ${env?.key === e.key ? 'active' : ''}`} onClick={() => selectEnv(e.key)}>
              <EnvDot color={e.color} /> {e.name}
            </button>
          ))}
        </div>
      </div>

      <ErrorBanner error={error ?? toggle.error} />
      {isLoading && <Spinner />}
      {data && data.items.length === 0 && (
        <EmptyState title={search || tag ? 'No matching flags' : archived ? 'No archived flags' : 'No flags yet'}>
          {!search && !tag && !archived && 'Create your first flag to decouple deploys from releases.'}
        </EmptyState>
      )}

      {data && data.items.length > 0 && (
        <div className={`table-wrap ${isFetching ? 'is-fetching' : ''}`}>
          <table className="table flags-table">
            <thead>
              <tr>
                <th>Flag</th>
                <th>{env?.name} targeting</th>
                {project?.environments.map((e) => (
                  <th key={e.key} className="col-toggle" title={e.name}><EnvDot color={e.color} /> {e.name.slice(0, 4)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.items.map((flag) => (
                <tr key={flag.key}>
                  <td>
                    <Link to={`/projects/${projectKey}/flags/${flag.key}${env ? `?env=${env.key}` : ''}`} className="flag-name">
                      {flag.name}
                    </Link>
                    <div className="flag-meta">
                      <code>{flag.key}</code>
                      <Badge>{flag.type.toLowerCase()}</Badge>
                      <LifecycleBadge lifecycle={flag.lifecycle} />
                      {flag.tags.map((t) => <Badge key={t} tone="neutral">#{t}</Badge>)}
                    </div>
                  </td>
                  <td className="muted summary-cell">
                    {env && flag.environments[env.key]?.summary}
                    {env && <div className="tiny">changed {timeAgo(flag.environments[env.key]?.updatedAt)}</div>}
                  </td>
                  {project?.environments.map((e) => {
                    const state = flag.environments[e.key];
                    return (
                      <td key={e.key} className="col-toggle">
                        {state && (
                          <Toggle size="sm" checked={state.enabled} disabled={!can('EDITOR') || flag.archived || toggle.isPending}
                                  label={`${flag.name} in ${e.name}`} onChange={(next) => onToggle(flag, e.key, next)} />
                        )}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data && data.totalPages > 1 && (
        <div className="pager">
          <button className="btn btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span className="muted">Page {page + 1} of {data.totalPages} · {data.totalElements} flags</span>
          <button className="btn btn-sm" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}

      {creating && <CreateFlagModal projectKey={projectKey} existingTags={tags ?? []} onClose={() => setCreating(false)} />}
    </div>
  );
}

const DEFAULT_VARIATIONS: Record<FlagType, string[]> = {
  BOOLEAN: ['true', 'false'],
  STRING: ['control', 'treatment'],
  NUMBER: ['0', '1'],
  JSON: ['{"enabled": false}', '{"enabled": true}'],
};

function CreateFlagModal({ projectKey, existingTags, onClose }: { projectKey: string; existingTags: string[]; onClose: () => void }) {
  const create = useCreateFlag(projectKey);
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [key, setKey] = useState('');
  const [keyTouched, setKeyTouched] = useState(false);
  const [description, setDescription] = useState('');
  const [type, setType] = useState<FlagType>('BOOLEAN');
  const [rawVariations, setRawVariations] = useState(DEFAULT_VARIATIONS.BOOLEAN);
  const [names, setNames] = useState<string[]>(['On', 'Off']);
  const [tags, setTags] = useState<string[]>([]);
  const [temporary, setTemporary] = useState(true);

  const effectiveKey = keyTouched ? key : slugify(name);
  const keyError = effectiveKey && !KEY_PATTERN.test(effectiveKey) ? 'lowercase letters, digits, . _ -' : undefined;
  const parsed = rawVariations.map((raw) => parseVariationValue(type, raw));
  const variationErrors = parsed.map((p) => p.error);
  const valid = name && effectiveKey && !keyError && variationErrors.every((e) => !e);

  const changeType = (t: FlagType) => {
    setType(t);
    setRawVariations(DEFAULT_VARIATIONS[t]);
    setNames(t === 'BOOLEAN' ? ['On', 'Off'] : ['', '']);
  };

  const submit = () => {
    const variations: Variation[] = parsed.map((p, i) => ({ value: p.value!, name: names[i] || null }));
    const body: CreateFlagBody = {
      key: effectiveKey, name, description, type, variations, tags, temporary,
      defaultOnVariation: 0, defaultOffVariation: type === 'BOOLEAN' ? 1 : 0,
    };
    create.mutate(body, { onSuccess: (flag) => navigate(`/projects/${projectKey}/flags/${flag.key}`) });
  };

  return (
    <Modal title="New feature flag" onClose={onClose} wide footer={
      <>
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={!valid || create.isPending} onClick={submit}>Create flag</button>
      </>
    }>
      <ErrorBanner error={create.error} />
      <div className="form-grid">
        <Field label="Name"><input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="New checkout flow" /></Field>
        <Field label="Key" hint="Referenced from code. Cannot be changed." error={keyError}>
          <input value={effectiveKey} onChange={(e) => { setKeyTouched(true); setKey(e.target.value); }} />
        </Field>
      </div>
      <Field label="Description"><textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
      <div className="form-grid">
        <Field label="Type">
          <select value={type} onChange={(e) => changeType(e.target.value as FlagType)}>
            <option value="BOOLEAN">Boolean</option>
            <option value="STRING">String</option>
            <option value="NUMBER">Number</option>
            <option value="JSON">JSON</option>
          </select>
        </Field>
        <Field label="Tags"><ValuesInput ariaLabel="Tags" values={tags} onChange={setTags} suggestions={existingTags} placeholder="Add tag…" /></Field>
      </div>

      <fieldset className="fieldset">
        <legend>Variations</legend>
        {rawVariations.map((raw, i) => (
          <div key={i} className="variation-row">
            <span className="variation-index">{i}</span>
            <input aria-label={`Variation ${i} name`} placeholder="Name (optional)" value={names[i] ?? ''}
                   onChange={(e) => setNames(names.map((n, j) => (j === i ? e.target.value : n)))} />
            {type === 'JSON' ? (
              <textarea aria-label={`Variation ${i} value`} rows={2} className="mono" value={raw}
                        onChange={(e) => setRawVariations(rawVariations.map((r, j) => (j === i ? e.target.value : r)))} />
            ) : (
              <input aria-label={`Variation ${i} value`} className="mono" value={raw} disabled={type === 'BOOLEAN'}
                     onChange={(e) => setRawVariations(rawVariations.map((r, j) => (j === i ? e.target.value : r)))} />
            )}
            {variationErrors[i] && <span className="field-error">{variationErrors[i]}</span>}
            {type !== 'BOOLEAN' && rawVariations.length > 2 && (
              <button type="button" className="icon-btn" aria-label={`Remove variation ${i}`}
                      onClick={() => { setRawVariations(rawVariations.filter((_, j) => j !== i)); setNames(names.filter((_, j) => j !== i)); }}>×</button>
            )}
          </div>
        ))}
        {type !== 'BOOLEAN' && (
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setRawVariations([...rawVariations, '']); setNames([...names, '']); }}>
            + Add variation
          </button>
        )}
        <p className="field-hint">New flags start <strong>off</strong> in every environment, serving variation {type === 'BOOLEAN' ? '1 (false)' : '0'}.
          {' '}Preview: {parsed.map((p) => formatValue(p.value)).join(', ')}</p>
      </fieldset>

      <label className="checkbox">
        <input type="checkbox" checked={temporary} onChange={(e) => setTemporary(e.target.checked)} />
        Temporary flag (release or experiment that should be removed later)
      </label>
    </Modal>
  );
}
