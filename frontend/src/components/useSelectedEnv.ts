import { useSearchParams } from 'react-router-dom';
import type { Environment } from '../api/types';

const storageKey = (projectKey: string) => `ffp.env.${projectKey}`;

/**
 * The environment being viewed lives in the URL (?env=) so views are shareable, and is
 * remembered per project so returning to a project reopens the same environment.
 */
export function useSelectedEnv(projectKey: string, environments: Environment[] | undefined) {
  const [params, setParams] = useSearchParams();
  let remembered: string | null = null;
  try {
    remembered = localStorage.getItem(storageKey(projectKey));
  } catch {
    /* ignore */
  }
  const requested = params.get('env') ?? remembered;
  const env = environments?.find((e) => e.key === requested) ?? environments?.[0];

  const select = (key: string) => {
    try {
      localStorage.setItem(storageKey(projectKey), key);
    } catch {
      /* ignore */
    }
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      next.set('env', key);
      return next;
    }, { replace: true });
  };
  return [env, select] as const;
}
