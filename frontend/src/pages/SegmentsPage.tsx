import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useDeleteSegment, useSaveSegment, useSegments } from '../api/hooks';
import type { Segment, SegmentRule } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Badge, EmptyState, ErrorBanner, Field, Modal, Spinner, ValuesInput } from '../components/ui';
import { KEY_PATTERN, slugify, timeAgo } from '../lib/format';
import { newClause, operatorLabel } from '../lib/targeting';
import { ClauseEditor } from './targeting/ClauseEditor';

export function SegmentsPage() {
  const { projectKey = '' } = useParams();
  const { data: segments, isLoading, error } = useSegments(projectKey);
  const remove = useDeleteSegment(projectKey);
  const { can } = useAuth();
  const [editing, setEditing] = useState<Segment | 'new' | null>(null);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Segments</h1>
          <p className="muted">Reusable audiences you can target from any flag rule.</p>
        </div>
        {can('EDITOR') && <button className="btn btn-primary" onClick={() => setEditing('new')}>New segment</button>}
      </header>
      <ErrorBanner error={error ?? remove.error} />
      {isLoading && <Spinner />}
      {segments?.length === 0 && <EmptyState title="No segments yet">Group users like “beta testers” or “enterprise customers” once, use everywhere.</EmptyState>}
      <div className="grid-cards">
        {segments?.map((s) => (
          <div key={s.key} className="card">
            <div className="section-head">
              <div>
                <h3>{s.name}</h3>
                <code className="muted">{s.key}</code>
              </div>
              {can('EDITOR') && (
                <div className="header-actions">
                  <button className="btn btn-sm" onClick={() => setEditing(s)}>Edit</button>
                  <button className="btn btn-sm btn-danger" disabled={s.usedByFlags.length > 0}
                          title={s.usedByFlags.length ? 'Used by flags' : undefined}
                          onClick={() => window.confirm(`Delete segment ${s.name}?`) && remove.mutate(s.key)}>Delete</button>
                </div>
              )}
            </div>
            {s.description && <p>{s.description}</p>}
            <ul className="segment-facts">
              <li>{s.included.length} included · {s.excluded.length} excluded</li>
              {s.rules.map((r, i) => (
                <li key={i} className="muted">
                  Rule: {r.clauses.map((c) => `${c.attribute} ${c.negate ? 'not ' : ''}${operatorLabel(c.operator)} ${c.values.join(', ')}`).join(' and ')}
                </li>
              ))}
            </ul>
            <div className="card-meta">
              {s.usedByFlags.length ? <span>Used by {s.usedByFlags.map((f) => <Badge key={f}>{f}</Badge>)}</span> : <span className="muted">Not used</span>}
              <span className="muted">updated {timeAgo(s.updatedAt)}</span>
            </div>
          </div>
        ))}
      </div>
      {editing && <SegmentModal projectKey={projectKey} segment={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </div>
  );
}

function SegmentModal({ projectKey, segment, onClose }: { projectKey: string; segment: Segment | null; onClose: () => void }) {
  const save = useSaveSegment(projectKey);
  const [name, setName] = useState(segment?.name ?? '');
  const [key, setKey] = useState(segment?.key ?? '');
  const [description, setDescription] = useState(segment?.description ?? '');
  const [included, setIncluded] = useState(segment?.included ?? []);
  const [excluded, setExcluded] = useState(segment?.excluded ?? []);
  const [rules, setRules] = useState<SegmentRule[]>(segment?.rules ?? []);
  const effectiveKey = segment ? segment.key : key || slugify(name);
  const keyError = effectiveKey && !KEY_PATTERN.test(effectiveKey) ? 'lowercase letters, digits, . _ -' : undefined;

  const updateRule = (i: number, rule: SegmentRule) => setRules(rules.map((r, j) => (j === i ? rule : r)));

  return (
    <Modal wide title={segment ? `Edit ${segment.name}` : 'New segment'} onClose={onClose} footer={
      <>
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={!name || !!keyError || save.isPending}
                onClick={() => save.mutate({ isNew: !segment, body: { key: effectiveKey, name, description, included, excluded, rules, version: segment?.version } },
                  { onSuccess: onClose })}>
          Save segment
        </button>
      </>
    }>
      <ErrorBanner error={save.error} />
      <div className="form-grid">
        <Field label="Name"><input autoFocus value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Key" error={keyError}><input value={effectiveKey} disabled={!!segment} onChange={(e) => setKey(e.target.value)} /></Field>
      </div>
      <Field label="Description"><input value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
      <Field label="Always include" hint="Context keys; paste a comma separated list to add many.">
        <ValuesInput ariaLabel="Included keys" values={included} onChange={setIncluded} placeholder="user key…" />
      </Field>
      <Field label="Always exclude" hint="Wins over inclusion and rules.">
        <ValuesInput ariaLabel="Excluded keys" values={excluded} onChange={setExcluded} placeholder="user key…" />
      </Field>
      <fieldset className="fieldset">
        <legend>Rules — contexts matching any rule are in the segment</legend>
        {rules.map((rule, i) => (
          <div key={i} className="rule">
            {rule.clauses.map((clause, j) => (
              <ClauseEditor key={j} index={j} clause={clause} segmentKeys={[]} allowSegments={false}
                            onChange={(c) => updateRule(i, { clauses: rule.clauses.map((x, k) => (k === j ? c : x)) })}
                            onRemove={() => updateRule(i, { clauses: rule.clauses.filter((_, k) => k !== j) })} />
            ))}
            <div className="header-actions">
              <button className="btn btn-ghost btn-sm" onClick={() => updateRule(i, { clauses: [...rule.clauses, newClause()] })}>+ Condition</button>
              <button className="btn btn-ghost btn-sm" onClick={() => setRules(rules.filter((_, k) => k !== i))}>Remove rule</button>
            </div>
          </div>
        ))}
        <button className="btn btn-sm" onClick={() => setRules([...rules, { clauses: [newClause()] }])}>+ Rule</button>
      </fieldset>
    </Modal>
  );
}
