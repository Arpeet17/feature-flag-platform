import { useState } from 'react';
import { useCreateUser, useUpdateUserRole, useUsers } from '../api/hooks';
import type { Role } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ErrorBanner, Field, Modal, Spinner } from '../components/ui';
import { timeAgo } from '../lib/format';

const ROLES: { value: Role; description: string }[] = [
  { value: 'VIEWER', description: 'Read-only access' },
  { value: 'EDITOR', description: 'Change flags, targeting and segments' },
  { value: 'ADMIN', description: 'Also manage projects, environments, SDK keys and users' },
];

export function UsersPage() {
  const { data: users, isLoading, error } = useUsers();
  const updateRole = useUpdateUserRole();
  const { user: me } = useAuth();
  const [creating, setCreating] = useState(false);

  return (
    <div className="page">
      <header className="page-header">
        <div><h1>Users</h1><p className="muted">Role-based access: admin ⊃ editor ⊃ viewer.</p></div>
        <button className="btn btn-primary" onClick={() => setCreating(true)}>Add user</button>
      </header>
      <ErrorBanner error={error ?? updateRole.error} />
      {isLoading && <Spinner />}
      <table className="table">
        <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Joined</th></tr></thead>
        <tbody>
          {users?.map((u) => (
            <tr key={u.id}>
              <td>{u.displayName}</td>
              <td>{u.email}</td>
              <td>
                <select aria-label={`Role of ${u.email}`} value={u.role} disabled={u.email === me?.email}
                        onChange={(e) => updateRole.mutate({ id: u.id, role: e.target.value as Role })}>
                  {ROLES.map((r) => <option key={r.value} value={r.value}>{r.value.toLowerCase()}</option>)}
                </select>
              </td>
              <td className="muted">{timeAgo(u.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {creating && <CreateUserModal onClose={() => setCreating(false)} />}
    </div>
  );
}

function CreateUserModal({ onClose }: { onClose: () => void }) {
  const create = useCreateUser();
  const [form, setForm] = useState({ email: '', displayName: '', password: '', role: 'VIEWER' as Role });
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });
  return (
    <Modal title="Add user" onClose={onClose} footer={
      <>
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={create.isPending} onClick={() => create.mutate(form, { onSuccess: onClose })}>Add user</button>
      </>
    }>
      <ErrorBanner error={create.error} />
      <Field label="Name"><input value={form.displayName} onChange={set('displayName')} /></Field>
      <Field label="Email"><input type="email" value={form.email} onChange={set('email')} /></Field>
      <Field label="Initial password" hint="At least 8 characters."><input type="password" value={form.password} onChange={set('password')} /></Field>
      <Field label="Role" hint={ROLES.find((r) => r.value === form.role)?.description}>
        <select value={form.role} onChange={set('role')}>{ROLES.map((r) => <option key={r.value} value={r.value}>{r.value.toLowerCase()}</option>)}</select>
      </Field>
    </Modal>
  );
}
