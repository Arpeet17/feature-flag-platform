import { SseParser } from './sse.js';
import type { EvaluationDetail, EvaluationReason, FlagClientOptions, JsonValue } from './types.js';

export type ResolvedOptions = Required<Omit<FlagClientOptions, 'logger'>> & Pick<FlagClientOptions, 'logger'>;

export function resolveOptions(options: FlagClientOptions): ResolvedOptions {
  return {
    streaming: true,
    reconnectDelayMs: 1_000,
    maxReconnectDelayMs: 30_000,
    fetch: globalThis.fetch.bind(globalThis),
    ...options,
    baseUrl: options.baseUrl.replace(/\/+$/, ''),
  };
}

export const toError = (e: unknown): Error => (e instanceof Error ? e : new Error(String(e)));

/** Minimal typed event emitter; a throwing listener can't break the client or other listeners. */
export class Emitter<Events extends object> {
  private readonly listeners = new Map<keyof Events, Set<(payload: never) => void>>();

  constructor(private readonly logger?: Pick<Console, 'warn'>) {}

  on<K extends keyof Events>(event: K, listener: (payload: Events[K]) => void): () => void {
    let set = this.listeners.get(event);
    if (!set) this.listeners.set(event, (set = new Set()));
    set.add(listener as (payload: never) => void);
    return () => set.delete(listener as (payload: never) => void);
  }

  emit<K extends keyof Events>(event: K, payload: Events[K]): void {
    const set = this.listeners.get(event);
    if (event === 'error' && (!set || set.size === 0)) this.logger?.warn?.(String((payload as Error).message));
    for (const listener of set ?? []) {
      try {
        (listener as (p: Events[K]) => void)(payload);
      } catch (e) {
        this.logger?.warn?.(`Flag listener threw: ${String(e)}`);
      }
    }
  }
}

/**
 * Coalesces concurrent calls of an async task: while one run is in flight, any number of
 * further calls queue exactly one follow-up run. A burst of change notifications therefore
 * costs at most two requests, and the last one always sees the latest state.
 */
export class Coalescer {
  private inFlight: Promise<void> | null = null;
  private queued = false;

  constructor(private readonly task: () => Promise<void>, private readonly onError: (e: Error) => void) {}

  run(): Promise<void> {
    if (this.inFlight) {
      this.queued = true;
      return this.inFlight;
    }
    this.inFlight = this.task()
      .catch((e) => this.onError(toError(e)))
      .finally(() => {
        this.inFlight = null;
        if (this.queued) {
          this.queued = false;
          void this.run();
        }
      });
    return this.inFlight;
  }
}

/**
 * Long-lived SSE connection to /api/v1/sdk/stream. Reconnects with exponential backoff and
 * full jitter, so a server restart doesn't trigger a synchronized reconnect stampede.
 */
export class ChangeStream {
  private controller: AbortController | null = null;
  private attempt = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private closed = false;

  constructor(
    private readonly options: ResolvedOptions,
    private readonly onEvent: (event: 'ready' | 'flags-changed', version: number) => void,
    private readonly onError: (e: Error) => void,
  ) {}

  start(): void {
    if (!this.controller && !this.closed) this.connect();
  }

  close(): void {
    this.closed = true;
    this.controller?.abort();
    this.controller = null;
    if (this.timer) clearTimeout(this.timer);
  }

  private connect(): void {
    const controller = new AbortController();
    this.controller = controller;
    this.consume(controller.signal)
      .catch((e) => {
        if (!controller.signal.aborted) this.onError(toError(e));
      })
      .finally(() => {
        if (this.controller === controller) this.controller = null;
        if (!this.closed && !controller.signal.aborted) this.scheduleReconnect();
      });
  }

  private async consume(signal: AbortSignal): Promise<void> {
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
        if (message.event === 'ready' || message.event === 'flags-changed') {
          if (message.event === 'ready') this.attempt = 0;
          this.onEvent(message.event, parseVersion(message.data));
        }
      }
    }
  }

  private scheduleReconnect(): void {
    const { reconnectDelayMs, maxReconnectDelayMs } = this.options;
    const ceiling = Math.min(maxReconnectDelayMs, reconnectDelayMs * 2 ** this.attempt++);
    const delay = Math.random() * ceiling; // full jitter
    this.options.logger?.debug?.(`Flag stream disconnected; reconnecting in ${Math.round(delay)}ms`);
    this.timer = setTimeout(() => {
      if (!this.closed) this.connect();
    }, delay);
  }
}

function parseVersion(data: string): number {
  try {
    return Number(JSON.parse(data).version);
  } catch {
    return NaN;
  }
}

export const REASONS = {
  NOT_READY: { kind: 'ERROR', errorKind: 'CLIENT_NOT_READY' } as EvaluationReason,
  NOT_FOUND: { kind: 'ERROR', errorKind: 'FLAG_NOT_FOUND' } as EvaluationReason,
  WRONG_TYPE: { kind: 'ERROR', errorKind: 'WRONG_TYPE' } as EvaluationReason,
};

/** Applies the caller's fallback for errors and type mismatches; never throws. */
export function typedDetail<T>(found: { value?: JsonValue | null; variation?: number | null; reason: EvaluationReason } | undefined,
                               fallback: T, isType: (v: unknown) => v is T): EvaluationDetail<T> {
  if (!found) return { value: fallback, variation: null, reason: REASONS.NOT_FOUND };
  if (found.reason.kind === 'ERROR' || found.variation == null) return { value: fallback, variation: null, reason: found.reason };
  if (!isType(found.value)) return { value: fallback, variation: null, reason: REASONS.WRONG_TYPE };
  return { value: found.value, variation: found.variation, reason: found.reason };
}

export const isBool = (v: unknown): v is boolean => typeof v === 'boolean';
export const isString = (v: unknown): v is string => typeof v === 'string';
export const isNumber = (v: unknown): v is number => typeof v === 'number';
export const isJson = <T>(v: unknown): v is T => v !== null && typeof v === 'object';

/** Type guard matching the fallback's JSON type; a null fallback accepts any type. */
export function sameTypeAs<T>(fallback: T): (v: unknown) => v is T {
  return (v: unknown): v is T => fallback === null || (v !== null && typeof v === typeof fallback);
}
