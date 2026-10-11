import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useCreateProject, useProjects } from '../api/hooks';
import type { EnvironmentWithSdkKey } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { SdkKeyReveal } from '../components/SdkKeyReveal';
import { EmptyState, EnvDot, ErrorBanner, Field, Modal, Spinner } from '../components/ui';
import { KEY_PATTERN, slugify, timeAgo } from '../lib/format';

export function ProjectsPage() {
  const { data: projects, isLoading, error } = useProjects();
  const { can } = useAuth();
  const [creating, setCreating] = useState(false);
  const [revealKeys, setRevealKeys] = useState<EnvironmentWithSdkKey[] | null>(null);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Projects</h1>
          <p className="muted">Each project has its own environments, flags and segments.</p>
        </div>
        {can('ADMIN') && <button className="btn btn-primary" onClick={() => setCreating(true)}>New project</button>}
      </header>
      <ErrorBanner error={error} />
      {isLoading && <Spinner />}
      {projects?.length === 0 && <EmptyState title="No projects yet">Create a project to start managing flags.</EmptyState>}
      <div className="grid-cards">
        {projects?.map((p) => (
          <Link key={p.key} to={`/projects/${p.key}/flags`} className="card card-link">
            <h3>{p.name}</h3>
            <code className="muted">{p.key}</code>
            {p.description && <p>{p.description}</p>}
            <div className="card-meta">
              <span>{p.flagCount} flag{p.flagCount === 1 ? '' : 's'}</span>
              <span className="env-list">{p.environments.map((e) => <EnvDot key={e.key} color={e.color} />)}</span>
              <span className="muted">updated {timeAgo(p.updatedAt)}</span>
            </div>
          </Link>
        ))}
      </div>
      {creating && (
        <CreateProjectModal onClose={() => setCreating(false)} onCreated={(keys) => { setCreating(false); setRevealKeys(keys); }} />
      )}
      {revealKeys && <SdkKeyReveal keys={revealKeys} onClose={() => setRevealKeys(null)} />}
    </div>
  );
}

function CreateProjectModal({ onClose, onCreated }: { onClose: () => void; onCreated: (keys: EnvironmentWithSdkKey[]) => void }) {
  const create = useCreateProject();
  const [name, setName] = useState('');
  const [key, setKey] = useState('');
  const [keyTouched, setKeyTouched] = useState(false);
  const [description, setDescription] = useState('');
  const effectiveKey = keyTouched ? key : slugify(name);
  const keyError = effectiveKey && !KEY_PATTERN.test(effectiveKey) ? 'lowercase letters, digits, . _ -' : undefined;

  return (
    <Modal title="New project" onClose={onClose} footer={
      <>
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={!name || !effectiveKey || !!keyError || create.isPending}
                onClick={() => create.mutate({ key: effectiveKey, name, description },
                  { onSuccess: (res) => onCreated(res.environments) })}>
          Create project
        </button>
      </>
    }>
      <ErrorBanner error={create.error} />
      <Field label="Name"><input autoFocus value={name} onChange={(e) => setName(e.target.value)} /></Field>
      <Field label="Key" hint="Used in URLs and the API. Cannot be changed." error={keyError}>
        <input value={effectiveKey} onChange={(e) => { setKeyTouched(true); setKey(e.target.value); }} />
      </Field>
      <Field label="Description"><textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
      <p className="muted">Development, Staging and Production environments are created automatically.</p>
    </Modal>
  );
}
