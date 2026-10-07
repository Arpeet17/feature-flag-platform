import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useAudit } from '../api/hooks';
import { JsonDiff } from '../components/JsonDiff';
import { Badge, EmptyState, ErrorBanner, Spinner } from '../components/ui';
import { formatDateTime, timeAgo } from '../lib/format';

const ACTION_TONE: Record<string, 'success' | 'danger' | 'warning' | 'info' | 'neutral'> = {
  CREATED: 'success', DELETED: 'danger', ARCHIVED: 'warning', TOGGLED: 'info', TARGETING_UPDATED: 'info', SDK_KEY_ROTATED: 'warning',
};

export function AuditPage() {
  const { projectKey = '' } = useParams();
  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Audit log</h1>
          <p className="muted">Every change, who made it, and exactly what changed.</p>
        </div>
      </header>
      <AuditList projectKey={projectKey} />
    </div>
  );
}

export function AuditList({ projectKey, resourceKey }: { projectKey: string; resourceKey?: string }) {
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<number | null>(null);
  const { data, isLoading, error } = useAudit(projectKey, { page, resourceKey });

  if (isLoading) return <Spinner />;
  return (
    <div>
      <ErrorBanner error={error} />
      {data?.items.length === 0 && <EmptyState title="No changes recorded yet" />}
      <ol className="audit">
        {data?.items.map((e) => (
          <li key={e.id} className="audit-item">
            <button className="audit-row" aria-expanded={expanded === e.id} onClick={() => setExpanded(expanded === e.id ? null : e.id)}>
              <Badge tone={ACTION_TONE[e.action] ?? 'neutral'}>{e.action.replace(/_/g, ' ').toLowerCase()}</Badge>
              <span className="audit-summary">
                <strong>{e.resourceKey}</strong> {e.summary}
                {e.environmentKey && <span className="muted"> · {e.environmentKey}</span>}
              </span>
              <span className="audit-actor muted">{e.actor}</span>
              <time className="muted tiny" dateTime={e.createdAt} title={formatDateTime(e.createdAt)}>{timeAgo(e.createdAt)}</time>
            </button>
            {expanded === e.id && <JsonDiff before={e.before} after={e.after} />}
          </li>
        ))}
      </ol>
      {data && data.totalPages > 1 && (
        <div className="pager">
          <button className="btn btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</button>
          <span className="muted">Page {page + 1} of {data.totalPages}</span>
          <button className="btn btn-sm" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Older</button>
        </div>
      )}
    </div>
  );
}
