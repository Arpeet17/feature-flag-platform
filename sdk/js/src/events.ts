import type { ResolvedOptions } from './internal.js';

type SdkEvent =
  | { kind: 'exposure'; contextKey: string; flagKey: string; variation: number; inExperiment: boolean; timestamp: number }
  | { kind: 'custom'; contextKey: string; eventKey: string; value?: number; timestamp: number };

export interface EventSettings {
  sendEvents: boolean;
  flushIntervalMs: number;
  maxQueueSize: number;
}

const MAX_BATCH = 1_000; // server limit per request
const MAX_DEDUPE_KEYS = 100_000;

/**
 * Buffers exposure and custom events and ships them in batches. Exposures are deduplicated per
 * (flag, context, variation) in memory: an app may read a flag on every request, but the
 * analysis only needs the first exposure (the server dedupes too; this just saves traffic).
 * Failed sends are re-queued; when the queue is full the oldest events are dropped and counted,
 * so a long outage can't exhaust memory.
 */
export class EventProcessor {
  private queue: SdkEvent[] = [];
  private readonly seen = new Set<string>();
  private timer: ReturnType<typeof setInterval> | null = null;
  private flushing: Promise<void> | null = null;
  dropped = 0;

  constructor(private readonly options: ResolvedOptions, private readonly settings: EventSettings,
              private readonly onError: (e: Error) => void) {
    if (settings.sendEvents) {
      this.timer = setInterval(() => void this.flush(), settings.flushIntervalMs);
      // Never keep a Node.js process alive just to flush analytics.
      (this.timer as { unref?: () => void }).unref?.();
    }
  }

  exposure(flagKey: string, contextKey: string, variation: number, inExperiment: boolean): void {
    if (!this.settings.sendEvents) return;
    const key = `${flagKey}\u0000${contextKey}\u0000${variation}`;
    if (this.seen.has(key)) return;
    if (this.seen.size >= MAX_DEDUPE_KEYS) this.seen.clear();
    this.seen.add(key);
    this.enqueue({ kind: 'exposure', contextKey, flagKey, variation, inExperiment, timestamp: Date.now() });
  }

  track(eventKey: string, contextKey: string, value?: number): void {
    if (!this.settings.sendEvents) return;
    this.enqueue({ kind: 'custom', contextKey, eventKey, ...(value === undefined ? {} : { value }), timestamp: Date.now() });
  }

  /** Sends everything queued. Concurrent calls share one flush. */
  flush(): Promise<void> {
    if (this.flushing) return this.flushing;
    this.flushing = this.send().finally(() => {
      this.flushing = null;
    });
    return this.flushing;
  }

  async close(): Promise<void> {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    await this.flush();
  }

  get pending(): number {
    return this.queue.length;
  }

  private enqueue(event: SdkEvent): void {
    this.queue.push(event);
    const overflow = this.queue.length - this.settings.maxQueueSize;
    if (overflow > 0) {
      this.queue.splice(0, overflow);
      this.dropped += overflow;
    }
  }

  private async send(): Promise<void> {
    while (this.queue.length > 0) {
      const batch = this.queue.splice(0, MAX_BATCH);
      try {
        const response = await this.options.fetch(`${this.options.baseUrl}/api/v1/sdk/events`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: this.options.sdkKey },
          body: JSON.stringify({ events: batch }),
        });
        if (response.status >= 500 || response.status === 429) throw new Error(`Event delivery failed with HTTP ${response.status}`);
        // 4xx other than 429 means the batch itself is bad: retrying can't help, so drop it.
        if (!response.ok) {
          this.dropped += batch.length;
          throw new Error(`Event batch rejected with HTTP ${response.status}`);
        }
      } catch (e) {
        if (!(e instanceof Error && e.message.startsWith('Event batch rejected'))) {
          this.queue.unshift(...batch); // retry on the next flush
          const overflow = this.queue.length - this.settings.maxQueueSize;
          if (overflow > 0) {
            this.queue.splice(0, overflow);
            this.dropped += overflow;
          }
        }
        this.onError(e instanceof Error ? e : new Error(String(e)));
        return;
      }
    }
  }
}
