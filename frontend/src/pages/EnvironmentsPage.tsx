import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useCreateEnvironment, useDeleteEnvironment, useProject, useRotateSdkKey } from '../api/hooks';
import type { EnvironmentWithSdkKey } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { SdkKeyReveal } from '../components/SdkKeyReveal';
import { EnvDot, ErrorBanner, Field, Modal, Spinner } from '../components/ui';
import { KEY_PATTERN, slugify, timeAgo } from '../lib/format';

export function EnvironmentsPage() {
  const { projectKey = '' } = useParams();
  const { data: project, isLoading } = useProject(projectKey);
  const rotate = useRotateSdkKey(projectKey);
  const remove = useDeleteEnvironment(projectKey);
  const { can } = useAuth();
  const [revealed, setRevealed] = useState<EnvironmentWithSdkKey[] | null>(null);
  const [creating, setCreating] = useState(false);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Environments</h1>
          <p className="muted">Each environment has independent flag state and its own SDK key.</p>
        </div>
        {can('ADMIN') && <button className="btn btn-primary" onClick={() => setCreating(true)}>New environment</button>}
      </header>
      <ErrorBanner error={rotate.error ?? remove.error} />
      {isLoading && <Spinner />}
      <table className="table">
        <thead><tr><th>Environment</th><th>Key</th><th>SDK key</th><th>Created</th><th /></tr></thead>
        <tbody>
          {project?.environments.map((e) => (
            <tr key={e.key}>
              <td><EnvDot color={e.color} /> {e.name}</td>
              <td><code>{e.key}</code></td>
              <td><code>{e.sdkKeyHint}</code></td>
              <td className="muted">{timeAgo(e.createdAt)}</td>
              <td className="row-actions">
                {can('ADMIN') && (
                  <>
                    <button className="btn btn-sm" onClick={() => window.confirm(`Rotate the ${e.name} SDK key? Apps using the old key will stop receiving flags within a minute.`)
                      && rotate.mutate(e.key, { onSuccess: (res) => setRevealed([res]) })}>Rotate key</button>
                    <button className="btn btn-sm btn-danger" onClick={() => window.confirm(`Delete ${e.name} and all its flag configuration?`) && remove.mutate(e.key)}>
                      Delete
                    </button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <section className="card section">
        <h3>Connecting an application</h3>
        <p className="muted">Server-side apps authenticate with an environment's SDK key. With the JavaScript SDK in <code>sdk/js</code>:</p>
        <pre className="code">{`import { FlagClient } from '@flagship/sdk';

const client = new FlagClient({ baseUrl: '${window.location.origin}', sdkKey: process.env.FLAG_SDK_KEY });
await client.identify({ key: user.id, attributes: { email: user.email, plan: user.plan } });

if (client.boolVariation('new-checkout', false)) {
  renderNewCheckout();
}
client.on('change', () => rerender()); // pushed over SSE within milliseconds`}</pre>
      </section>

      {creating && <CreateEnvironmentModal projectKey={projectKey} onClose={() => setCreating(false)}
                                          onCreated={(res) => { setCreating(false); setRevealed([res]); }} />}
      {revealed && <SdkKeyReveal keys={revealed} onClose={() => setRevealed(null)} />}
    </div>
  );
}

function CreateEnvironmentModal({ projectKey, onClose, onCreated }: { projectKey: string; onClose: () => void; onCreated: (r: EnvironmentWithSdkKey) => void }) {
  const create = useCreateEnvironment(projectKey);
  const [name, setName] = useState('');
  const [color, setColor] = useState('#6366f1');
  const key = slugify(name);
  return (
    <Modal title="New environment" onClose={onClose} footer={
      <>
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={!name || !KEY_PATTERN.test(key) || create.isPending}
                onClick={() => create.mutate({ key, name, color }, { onSuccess: onCreated })}>Create</button>
      </>
    }>
      <ErrorBanner error={create.error} />
      <Field label="Name" hint={key && <>Key: <code>{key}</code></>}><input autoFocus value={name} onChange={(e) => setName(e.target.value)} /></Field>
      <Field label="Color"><input type="color" value={color} onChange={(e) => setColor(e.target.value)} /></Field>
      <p className="muted tiny">All existing flags are added to the new environment, turned off.</p>
    </Modal>
  );
}
