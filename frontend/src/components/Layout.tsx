import { NavLink, Outlet, useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useProjects } from '../api/hooks';

const NAV = [
  { to: 'flags', label: 'Flags', icon: '⚑' },
  { to: 'segments', label: 'Segments', icon: '◎' },
  { to: 'rollouts', label: 'Rollouts', icon: '⇡' },
  { to: 'experiments', label: 'Experiments', icon: '⚗' },
  { to: 'debugger', label: 'Debugger', icon: '⚙' },
  { to: 'audit', label: 'Audit log', icon: '☰' },
  { to: 'environments', label: 'Environments', icon: '▤' },
];

export function Layout() {
  const { user, logout, can } = useAuth();
  const { projectKey } = useParams();
  const { data: projects } = useProjects();
  const navigate = useNavigate();

  return (
    <div className="shell">
      <aside className="sidebar">
        <NavLink to="/" className="brand">
          <span className="brand-mark" aria-hidden>⚑</span> Flagship
        </NavLink>

        {projects && projects.length > 0 && (
          <label className="project-switcher">
            <span className="field-label">Project</span>
            <select value={projectKey ?? ''} onChange={(e) => navigate(e.target.value ? `/projects/${e.target.value}/flags` : '/')}>
              {!projectKey && <option value="">Select a project…</option>}
              {projects.map((p) => <option key={p.key} value={p.key}>{p.name}</option>)}
            </select>
          </label>
        )}

        <nav className="nav" aria-label="Main">
          {projectKey && NAV.map((item) => (
            <NavLink key={item.to} to={`/projects/${projectKey}/${item.to}`} className="nav-link">
              <span className="nav-icon" aria-hidden>{item.icon}</span>{item.label}
            </NavLink>
          ))}
          <div className="nav-divider" />
          <NavLink to="/" end className="nav-link"><span className="nav-icon" aria-hidden>▦</span>All projects</NavLink>
          {can('ADMIN') && <NavLink to="/users" className="nav-link"><span className="nav-icon" aria-hidden>☺</span>Users</NavLink>}
          <a href="/swagger-ui.html" target="_blank" rel="noreferrer" className="nav-link">
            <span className="nav-icon" aria-hidden>↗</span>API docs
          </a>
        </nav>

        {user && (
          <div className="user-card">
            <div>
              <div className="user-name">{user.displayName}</div>
              <div className="user-meta">{user.email} · {user.role.toLowerCase()}</div>
            </div>
            <button className="btn btn-ghost btn-sm" onClick={logout}>Sign out</button>
          </div>
        )}
      </aside>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
