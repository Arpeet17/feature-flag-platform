import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ErrorBanner, Field } from '../components/ui';

const DEMO_ACCOUNTS = [
  { email: 'admin@ffp.local', password: 'admin123', role: 'Admin' },
  { email: 'editor@ffp.local', password: 'editor123', role: 'Editor' },
  { email: 'viewer@ffp.local', password: 'viewer123', role: 'Viewer' },
];

export function LoginPage() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  const from = (location.state as { from?: string } | null)?.from ?? '/';
  if (user) return <Navigate to={from} replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(email, password);
      navigate(from, { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="login">
      <form className="card login-card" onSubmit={submit}>
        <div className="brand brand-lg"><span className="brand-mark" aria-hidden>⚑</span> Flagship</div>
        <p className="muted">Release safely. Target precisely. Roll back instantly.</p>
        <ErrorBanner error={error} />
        <Field label="Email">
          <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </Field>
        <Field label="Password">
          <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </Field>
        <button className="btn btn-primary btn-block" disabled={submitting}>{submitting ? 'Signing in…' : 'Sign in'}</button>
        <div className="demo-accounts">
          <span className="muted">Demo accounts:</span>
          {DEMO_ACCOUNTS.map((a) => (
            <button type="button" key={a.email} className="btn btn-ghost btn-sm"
                    onClick={() => { setEmail(a.email); setPassword(a.password); }}>
              {a.role}
            </button>
          ))}
        </div>
      </form>
    </div>
  );
}
