import { Evaluator } from './engine/evaluator.js';
import type { Ruleset } from './engine/model.js';
import { EventProcessor } from './events.js';
import { ChangeStream, Coalescer, Emitter, REASONS, eventSettings, isBool, isJson, isNumber, isString, resolveOptions, sameTypeAs, typedDetail, type ResolvedOptions } from './internal.js';
import type { ClientEvents, EvaluationContext, EvaluationDetail, FlagClientOptions, JsonValue } from './types.js';

/**
 * Local-evaluation client for backend services: downloads the environment's ruleset once and
 * evaluates every flag in-process, for any number of users, with zero network calls per
 * evaluation. The evaluator is a port of the server's engine and is held to the same shared
 * conformance vectors, so a user gets the same variation here as from the server.
 *
 * Ruleset refreshes are triggered by the SSE stream and use conditional requests (ETag), so
 * an unrelated reconnect costs a 304 and no payload. If the platform is unreachable, the last
 * ruleset keeps serving indefinitely.
 */
export class LocalFlagClient {
  private readonly options: ResolvedOptions;
  private readonly events: Emitter<ClientEvents>;
  private readonly refresher: Coalescer;
  private readonly stream: ChangeStream;
  private readonly events$: EventProcessor;
  private evaluator: Evaluator | null = null;
  private ruleset: Ruleset | null = null;
  private etag: string | null = null;

  constructor(options: FlagClientOptions) {
    this.options = resolveOptions(options);
    this.events = new Emitter<ClientEvents>(this.options.logger);
    this.refresher = new Coalescer(() => this.fetchRuleset(), (e) => this.events.emit('error', e));
    this.stream = new ChangeStream(this.options, (event, version) => {
      if (event === 'flags-changed' || version !== this.ruleset?.version) void this.refresher.run();
    }, (e) => this.events.emit('error', e));
    this.events$ = new EventProcessor(this.options, eventSettings(this.options), (e) => this.events.emit('error', e));
  }

  /** Loads the ruleset (rejects if the first load fails) and starts listening for changes. */
  async start(): Promise<void> {
    let failure: Error | null = null;
    const off = this.events.on('error', (e) => (failure = e));
    await this.refresher.run();
    off();
    if (!this.evaluator) throw failure ?? new Error('Ruleset could not be loaded');
    this.events.emit('ready', undefined);
    if (this.options.streaming) this.stream.start();
  }

  isReady(): boolean {
    return this.evaluator !== null;
  }

  /** Version of the ruleset currently in use (changes whenever any flag or segment changes). */
  get rulesetVersion(): number | null {
    return this.ruleset?.version ?? null;
  }

  boolVariation(key: string, context: EvaluationContext, fallback: boolean): boolean {
    return this.detail(key, context, fallback, isBool).value;
  }

  stringVariation(key: string, context: EvaluationContext, fallback: string): string {
    return this.detail(key, context, fallback, isString).value;
  }

  numberVariation(key: string, context: EvaluationContext, fallback: number): number {
    return this.detail(key, context, fallback, isNumber).value;
  }

  jsonVariation<T extends JsonValue>(key: string, context: EvaluationContext, fallback: T): T {
    return this.detail(key, context, fallback, isJson<T>).value;
  }

  variationDetail<T extends JsonValue>(key: string, context: EvaluationContext, fallback: T): EvaluationDetail<T> {
    return this.detail(key, context, fallback, sameTypeAs(fallback));
  }

  /** Every flag's value for one context, e.g. to bootstrap a browser session server-side. */
  allFlags(context: EvaluationContext): Record<string, JsonValue | null> {
    if (!this.evaluator || !this.ruleset) return {};
    const evaluator = this.evaluator;
    return Object.fromEntries(Object.keys(this.ruleset.flags).sort().map((k) => [k, evaluator.evaluate(k, context).value]));
  }

  /** Records a custom event (e.g. 'purchase', with an optional numeric value) for experiment metrics. */
  track(eventKey: string, context: EvaluationContext, value?: number): void {
    this.events$.track(eventKey, context.key, value);
  }

  /** Sends queued events now (e.g. before a serverless function returns). */
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

  private detail<T>(key: string, context: EvaluationContext, fallback: T, isType: (v: unknown) => v is T): EvaluationDetail<T> {
    if (!this.evaluator) return { value: fallback, variation: null, reason: REASONS.NOT_READY };
    const result = this.evaluator.evaluate(key, context);
    if (result.variation !== null) this.events$.exposure(key, context.key, result.variation, result.reason.inRollout === true);
    return typedDetail(result.reason.errorKind === 'FLAG_NOT_FOUND' ? undefined : result, fallback, isType);
  }

  private async fetchRuleset(): Promise<void> {
    const headers: Record<string, string> = { Accept: 'application/json', Authorization: this.options.sdkKey };
    if (this.etag) headers['If-None-Match'] = this.etag;
    const response = await this.options.fetch(`${this.options.baseUrl}/api/v1/sdk/rules`, { headers });
    if (response.status === 304) return;
    if (!response.ok) throw new Error(`Ruleset download failed with HTTP ${response.status}`);
    const next = (await response.json()) as Ruleset;
    const previous = this.ruleset;
    // Swap atomically: evaluations in progress keep using the evaluator they started with.
    this.ruleset = next;
    this.evaluator = new Evaluator(next);
    this.etag = response.headers.get('ETag');
    if (previous) {
      const changed = changedFlags(previous, next);
      if (changed.length > 0) this.events.emit('change', { changed });
    }
  }
}

/** Flags added, removed or modified; a segment change marks every flag (cheap and always correct). */
function changedFlags(before: Ruleset, after: Ruleset): string[] {
  const keys = new Set([...Object.keys(before.flags), ...Object.keys(after.flags)]);
  const segmentsChanged = JSON.stringify(before.segments) !== JSON.stringify(after.segments);
  return [...keys].filter((k) => segmentsChanged
    || JSON.stringify(before.flags[k]) !== JSON.stringify(after.flags[k])).sort();
}
