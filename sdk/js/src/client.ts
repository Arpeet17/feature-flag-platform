import { SseParser } from './sse.js';
import type {
  ChangeEvent, ClientEvents, EvaluationContext, EvaluationDetail, EvaluationReason, FlagClientOptions, FlagValue, JsonValue,
} from './types.js';

type Listener<T> = (payload: T) => void;

const NOT_READY: EvaluationReason = { kind: 'ERROR', errorKind: 'CLIENT_NOT_READY' };
const NOT_FOUND: EvaluationReason = { kind: 'ERROR', errorKind: 'FLAG_NOT_FOUND' };
const WRONG_TYPE: EvaluationReason = { kind: 'ERROR', errorKind: 'WRONG_TYPE' };

/**
 * Feature flag client.
 *
 * Design principles:
 *  - Reading a flag is synchronous and never throws: it uses the last known evaluations and
 *    falls back to the caller's default, so a platform outage can't take the app down.
 *  - Updates are pushed over Server-Sent Events; on change the client re-evaluates for the
 *    current context and emits 'change' with exactly the keys whose value changed.
 *  - Lost connections are retried with exponential backoff and full jitter to avoid
 *    thundering-herd reconnects after a server restart.
 */
export class FlagClient {
  private readonly options: Required<Omit<FlagClientOptions, 'logger'>> & Pick<FlagClientOptions, 'logger'>;
  private readonly listeners: { [K in keyof ClientEvents]: Set<Listener<ClientEvents[K]>> } = {
    ready: new Set(), change: new Set(), error: new Set(),
  };
  private context: EvaluationContext | null = null;
  private flags: Record<string, FlagValue> = {};
  private version = -1;
  private ready = false;
  private closed = false;
  private stream: AbortController | null = null;
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private refreshInFlight: Promise<void> | null = null;
  private refreshQueued = false;

  constructor(options: FlagClientOptions) {
    this.options = {
      streaming: true,
      reconnectDelayMs: 1_000,
      maxReconnectDelayMs: 30_000,
      fetch: globalThis.fetch.bind(globalThis),
      ...options,
      baseUrl: options.baseUrl.replace(/\/+$/, ''),
    };
  }

  /** Sets the context and loads its evaluations. Starts streaming on first call. */
  async identify(context: EvaluationContext): Promise<void> {
    this.context = context;
    await this.refresh();
    if (!this.ready) {
      this.ready = true;
      this.emit('ready', undefined);
    }
    if (this.options.streaming && !this.stream && !this.closed) this.connect();
  }

  isReady(): boolean {
    return this.ready;
  }

  boolVariation(key: string, fallback: boolean): boolean {
    return this.typed(key, fallback, (v): v is boolean => typeof v === 'boolean').value;
  }

  stringVariation(key: string, fallback: string): string {
    return this.typed(key, fallback, (v): v is string => typeof v === 'string').value;
  }

  numberVariation(key: string, fallback: number): number {
    return this.typed(key, fallback, (v): v is number => typeof v === 'number').value;
  }

  jsonVariation<T extends JsonValue>(key: string, fallback: T): T {
    return this.typed(key, fallback, (v): v is T => v !== null && typeof v === 'object').value;
  }

  /** Value plus the variation index and the reason it was served – useful for logging experiments. */
  variationDetail<T extends JsonValue>(key: string, fallback: T): EvaluationDetail<T> {
    return this.typed(key, fallback, (v): v is T => typeof v === typeof fallback);
  }

  allFlags(): Record<string, JsonValue | undefined> {
    return Object.fromEntries(Object.entries(this.flags).map(([k, v]) => [k, v.value]));
  }

  on<K extends keyof ClientEvents>(event: K, listener: Listener<ClientEvents[K]>): () => void {
    this.listeners[event].add(listener);
    return () => this.listeners[event].delete(listener);
  }

  close(): void {
    this.closed = true;
    this.stream?.abort();
    this.stream = null;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
  }

  // ------------------------------------------------------------------ internals

  private typed<T>(key: string, fallback: T, isType: (v: unknown) => v is T): EvaluationDetail<T> {
    if (!this.ready) return { value: fallback, variation: null, reason: NOT_READY };
    const flag = this.flags[key];
    if (!flag) return { value: fallback, variation: null, reason: NOT_FOUND };
    if (flag.reason.kind === 'ERROR' || flag.variation == null) return { value: fallback, variation: null, reason: flag.reason };
    if (!isType(flag.value)) return { value: fallback, variation: null, reason: WRONG_TYPE };
    return { value: flag.value, variation: flag.variation, reason: flag.reason };
  }

  /**
   * Re-evaluates for the current context. Concurrent calls coalesce: while a request is in
   * flight at most one follow-up is queued, so a burst of change events costs two requests.
   */
  private refresh(): Promise<void> {
    if (this.refreshInFlight) {
      this.refreshQueued = true;
      return this.refreshInFlight;
    }
    this.refreshInFlight = this.fetchEvaluations()
      .catch((e) => this.emit('error', toError(e)))
      .finally(() => {
        this.refreshInFlight = null;
        if (this.refreshQueued) {
          this.refreshQueued = false;
          void this.refresh();
        }
      });
    return this.refreshInFlight;
  }

  private async fetchEvaluations(): Promise<void> {
    if (!this.context) return;
    const response = await this.options.fetch(`${this.options.baseUrl}/api/v1/sdk/evaluate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: this.options.sdkKey },
      body: JSON.stringify({ context: this.context }),
    });
    if (!response.ok) throw new Error(`Flag evaluation failed with HTTP ${response.status}`);
    const body = (await response.json()) as { version: number; flags: Record<string, FlagValue> };
    const changed = diff(this.flags, body.flags);
    this.flags = body.flags;
    this.version = body.version;
    if (this.ready && changed.length > 0) this.emit('change', { changed } satisfies ChangeEvent);
  }

  private connect(): void {
    const controller = new AbortController();
    this.stream = controller;
    this.consumeStream(controller.signal)
      .catch((e) => {
        if (!controller.signal.aborted) this.emit('error', toError(e));
      })
      .finally(() => {
        if (this.stream === controller) this.stream = null;
        if (!this.closed && !controller.signal.aborted) this.scheduleReconnect();
      });
  }

  private async consumeStream(signal: AbortSignal): Promise<void> {
    const response = await this.options.fetch(`${this.options.baseUrl}/api/v1/sdk/stream`, {
      headers: { Accept: 'text/event-stream', Authorization: this.options.sdkKey },
      signal,
    });
    if (!response.ok || !response.body) throw new Error(`Stream connection failed with HTTP ${response.status}`);

    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
    const parser = new SseParser();
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      for (const message of parser.push(value)) {
        if (message.event === 'ready') {
          this.reconnectAttempt = 0;
          // Catch up on anything missed while disconnected.
          if (parseVersion(message.data) !== this.version) void this.refresh();
        } else if (message.event === 'flags-changed') {
          void this.refresh();
        }
      }
    }
  }

  private scheduleReconnect(): void {
    const { reconnectDelayMs, maxReconnectDelayMs } = this.options;
    const ceiling = Math.min(maxReconnectDelayMs, reconnectDelayMs * 2 ** this.reconnectAttempt++);
    const delay = Math.random() * ceiling; // "full jitter"
    this.options.logger?.debug?.(`Flag stream disconnected; reconnecting in ${Math.round(delay)}ms`);
    this.reconnectTimer = setTimeout(() => {
      if (!this.closed) this.connect();
    }, delay);
  }

  private emit<K extends keyof ClientEvents>(event: K, payload: ClientEvents[K]): void {
    if (event === 'error' && this.listeners.error.size === 0) {
      this.options.logger?.warn?.(String((payload as Error).message));
    }
    for (const listener of this.listeners[event]) {
      try {
        listener(payload);
      } catch (e) {
        this.options.logger?.warn?.(`Flag listener threw: ${String(e)}`);
      }
    }
  }
}

function diff(before: Record<string, FlagValue>, after: Record<string, FlagValue>): string[] {
  const keys = new Set([...Object.keys(before), ...Object.keys(after)]);
  return [...keys].filter((k) => before[k]?.variation !== after[k]?.variation
    || JSON.stringify(before[k]?.value) !== JSON.stringify(after[k]?.value)).sort();
}

function parseVersion(data: string): number {
  try {
    return Number(JSON.parse(data).version);
  } catch {
    return NaN;
  }
}

function toError(e: unknown): Error {
  return e instanceof Error ? e : new Error(String(e));
}
