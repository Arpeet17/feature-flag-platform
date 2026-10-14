import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { Spinner } from './components/ui';
import { AuditPage } from './pages/AuditPage';
import { DebuggerPage } from './pages/DebuggerPage';
import { EnvironmentsPage } from './pages/EnvironmentsPage';
import { ExperimentDetailPage } from './pages/ExperimentDetailPage';
import { ExperimentsPage } from './pages/ExperimentsPage';
import { FlagDetailPage } from './pages/FlagDetailPage';
import { FlagsPage } from './pages/FlagsPage';
import { LoginPage } from './pages/LoginPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { SegmentsPage } from './pages/SegmentsPage';
import { UsersPage } from './pages/UsersPage';

function RequireAuth() {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <div className="center"><Spinner /></div>;
  if (!user) return <Navigate to="/login" state={{ from: location.pathname + location.search }} replace />;
  return <Outlet />;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route index element={<ProjectsPage />} />
          <Route path="users" element={<UsersPage />} />
          <Route path="projects/:projectKey">
            <Route index element={<Navigate to="flags" replace />} />
            <Route path="flags" element={<FlagsPage />} />
            <Route path="flags/:flagKey" element={<FlagDetailPage />} />
            <Route path="segments" element={<SegmentsPage />} />
            <Route path="experiments" element={<ExperimentsPage />} />
            <Route path="experiments/:experimentKey" element={<ExperimentDetailPage />} />
            <Route path="environments" element={<EnvironmentsPage />} />
            <Route path="audit" element={<AuditPage />} />
            <Route path="debugger" element={<DebuggerPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
