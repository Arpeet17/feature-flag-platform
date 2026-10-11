const TOKEN_KEY = 'ffp.token';

/** Error carrying an RFC 9457 problem response from the API. */
export class ApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly errors: string[];

  constructor(status: number, title: string, detail: string, errors: string[] = []) {
    super(detail || title);
    this.status = status;
    this.title = title;
    this.errors = errors;
  }

  get isConflict() {
    return this.status === 409;
  }
}

export const tokenStore = {
  get: (): string | null => {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  set: (token: string | null) => {
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token);
      else localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* storage unavailable: session-only login */
    }
  },
};

let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

export async function api<T>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const token = tokenStore.get();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  let body = init.body;
  if (init.json !== undefined) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(init.json);
  }

  const response = await fetch(`/api/v1${path}`, { ...init, headers, body });
  if (response.status === 204) return undefined as T;

  const text = await response.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith('/auth/login')) onUnauthorized();
    throw new ApiError(response.status, data?.title ?? response.statusText, data?.detail ?? '', data?.errors ?? []);
  }
  return data as T;
}
