import { EventProcessor } from './events.js';
import { ChangeStream, Coalescer, Emitter, REASONS, eventSettings, isBool, isJson, isNumber, isString, resolveOptions, sameTypeAs, typedDetail, type ResolvedOptions } from './internal.js';
import type { ChangeEvent, ClientEvents, EvaluationContext, EvaluationDetail, FlagClientOptions, FlagValue, JsonValue } from './types.js';

/**
 * Remote-evaluation client: the server evaluates flags for one context (the current user) and
 * the client caches the results. Suited to user-facing apps where one process serves one user.
 * For backend services evaluating many users, use {@link LocalFlagClient}.
 *
 * Design principles:
 *  - Reading a flag is synchronous and never throws: it uses the last known evaluations and
 *    falls back to the caller's default, so a platform outage can't take the app down.
 *  - Updates are pushed over Server-Sent Events; on change the client re-evaluates for the
 *    current context and emits 'change' with exactly the keys whose value changed.
 */
export class FlagClient {
  private readonly options: ResolvedOptions;
  private readonly events: Emitter<ClientEvents>;
  private readonly refresher: Coalescer;
  private readonly stream: ChangeStream;
  private readonly events$: EventProcessor;
  private context: EvaluationContext | null = null;
  private flags: Record<string, FlagValue> = {};
  private version = -1;
  private ready = false;
  private streaming = false;

  constructor(options: FlagClientOptions) {
    this.options = resolveOptions(options);
    this.events = new Emitter<ClientEvents>(this.options.logger);
    this.refresher = new Coalescer(() => this.fetchEvaluations(), (e) => this.events.emit('error', e));
    this.stream = new ChangeStream(this.options, (event, version) => {
      // 'ready' after a reconnect: catch up on anything missed while disconnected.
      if (event === 'flags-changed' || version !== this.version) void this.refresher.run();
    }, (e) => this.events.emit('error', e));
    this.events$ = new EventProcessor(this.options, eventSettings(this.options), (e) => this.events.emit('error', e));
  }

  /** Sets the context and loads its evaluations. Starts streaming on first call. */
  async identify(context: EvaluationContext): Promise<void> {
    this.context = context;
    await this.refresher.run();
    if (!this.ready) {
      this.ready = true;
      this.events.emit('ready', undefined);
    }
    if (this.options.streaming && !this.streaming) {
      this.streaming = true;
      this.stream.start();
    }
  }

  isReady(): boolean {
    return this.ready;
  }

  boolVariation(key: string, fallback: boolean): boolean {
    return this.detail(key, fallback, isBool).value;
  }

  stringVariation(key: string, fallback: string): string {
    return this.detail(key, fallback, isString).value;
  }

  numberVariation(key: string, fallback: number): number {
    return this.detail(key, fallback, isNumber).value;
  }

  jsonVariation<T extends JsonValue>(key: string, fallback: T): T {
    return this.detail(key, fallback, isJson<T>).value;
  }

  /** Value plus the variation index and the reason it was served – useful for logging experiments. */
  variationDetail<T extends JsonValue>(key: string, fallback: T): EvaluationDetail<T> {
    return this.detail(key, fallback, sameTypeAs(fallback));
  }

  allFlags(): Record<string, JsonValue | undefined> {
    return Object.fromEntries(Object.entries(this.flags).map(([k, v]) => [k, v.value]));
  }

  /** Records a custom event for the current context (e.g. 'purchase', with an optional value). */
  track(eventKey: string, value?: number): void {
    if (this.context) this.events$.track(eventKey, this.context.key, value);
  }

  flush(): Promise<void> {
    return this.events$.flush();
  }

  on<K extends keyof ClientEvents>(event: K, listener: (payload: ClientEvents[K]) => void): () => void {
    return this.events.on(event, listener);
  }

  /** Stops streaming and flushes queued events. */
  async close(): Promise<void> {
    this.stream.close();
    await this.events$.close();
  }

  private detail<T>(key: string, fallback: T, isType: (v: unknown) => v is T): EvaluationDetail<T> {
    if (!this.ready) return { value: fallback, variation: null, reason: REASONS.NOT_READY };
    const found = this.flags[key];
    if (found && found.variation != null && this.context) {
      this.events$.exposure(key, this.context.key, found.variation, found.reason.inRollout === true);
    }
    return typedDetail(found, fallback, isType);
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
    if (this.ready && changed.length > 0) this.events.emit('change', { changed } satisfies ChangeEvent);
  }
}

function diff(before: Record<string, FlagValue>, after: Record<string, FlagValue>): string[] {
  const keys = new Set([...Object.keys(before), ...Object.keys(after)]);
  return [...keys].filter((k) => before[k]?.variation !== after[k]?.variation
    || JSON.stringify(before[k]?.value) !== JSON.stringify(after[k]?.value)).sort();
}
