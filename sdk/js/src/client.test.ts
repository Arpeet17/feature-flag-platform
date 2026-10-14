import { afterEach, describe, expect, it, vi } from 'vitest';
import { FlagClient } from './client.js';
import type { FlagValue } from './types.js';

type Flags = Record<string, FlagValue>;

/** A fake platform: serves evaluations and an SSE stream the test can push events into. */
function fakeServer(initial: Flags) {
  let flags = initial;
  let version = 1;
  let streamController: ReadableStreamDefaultController<Uint8Array> | null = null;
  const encoder = new TextEncoder();
  const requests: { url: string; body?: unknown; auth: string | null }[] = [];
  let gate: Promise<void> | null = null;
  let failStreams = 0;

  const fetchImpl = vi.fn(async (url: string, init: RequestInit = {}) => {
    const headers = new Headers(init.headers);
    requests.push({ url, body: init.body ? JSON.parse(String(init.body)) : undefined, auth: headers.get('Authorization') });
    if (url.endsWith('/evaluate')) {
      if (gate) await gate;
      return new Response(JSON.stringify({ version, flags }), { status: 200 });
    }
    if (failStreams > 0) {
      failStreams--;
      throw new Error('connection refused');
    }
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        streamController = controller;
        controller.enqueue(encoder.encode(`event:ready\ndata:{"version":${version}}\n\n`));
      },
    });
    init.signal?.addEventListener('abort', () => streamController?.error(new DOMException('aborted', 'AbortError')));
    return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
  });

  return {
    fetch: fetchImpl as unknown as typeof fetch,
    requests,
    evaluations: () => requests.filter((r) => r.url.endsWith('/evaluate')).length,
    streams: () => requests.filter((r) => r.url.endsWith('/stream')).length,
    failNextStreams(n: number) {
      failStreams = n;
    },
    /** Holds evaluation responses until the returned function is called. */
    hold() {
      let release!: () => void;
      gate = new Promise((r) => { release = r; });
      return () => { gate = null; release(); };
    },
    change(next: Flags) {
      flags = next;
      version++;
      streamController?.enqueue(encoder.encode(`event:flags-changed\ndata:{"version":${version}}\n\n`));
    },
  };
}

const flag = (value: FlagValue['value'], variation = 0, kind: FlagValue['reason']['kind'] = 'FALLTHROUGH'): FlagValue =>
  ({ value, variation, reason: { kind }, version: 1 });

const tick = () => new Promise((r) => setTimeout(r, 10));

describe('FlagClient', () => {
  let client: FlagClient | undefined;
  afterEach(() => client?.close());

  it('returns defaults before identify and evaluated values after', async () => {
    const server = fakeServer({ 'new-checkout': flag(true), banner: flag('Sale!'), limit: flag(50) });
    client = new FlagClient({ baseUrl: 'https://flags.test/', sdkKey: 'sdk-123', fetch: server.fetch, streaming: false });

    expect(client.boolVariation('new-checkout', false)).toBe(false);
    await client.identify({ key: 'user-1', attributes: { plan: 'pro' } });

    expect(client.isReady()).toBe(true);
    expect(client.boolVariation('new-checkout', false)).toBe(true);
    expect(client.stringVariation('banner', '')).toBe('Sale!');
    expect(client.numberVariation('limit', 10)).toBe(50);
    expect(server.requests[0]).toMatchObject({
      url: 'https://flags.test/api/v1/sdk/evaluate',
      auth: 'sdk-123',
      body: { context: { key: 'user-1', attributes: { plan: 'pro' } } },
    });
  });

  it('falls back to the default on unknown flags, errors and type mismatches', async () => {
    const server = fakeServer({
      text: flag('hello'),
      broken: { reason: { kind: 'ERROR', errorKind: 'MALFORMED_FLAG' }, version: 1 },
    });
    client = new FlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch, streaming: false });
    await client.identify({ key: 'u' });

    expect(client.boolVariation('missing', true)).toBe(true);
    expect(client.variationDetail('missing', true).reason.errorKind).toBe('FLAG_NOT_FOUND');
    expect(client.boolVariation('broken', true)).toBe(true);
    expect(client.boolVariation('text', false)).toBe(false);
    expect(client.variationDetail('text', false).reason.errorKind).toBe('WRONG_TYPE');
  });

  it('keeps serving last known values when the platform is unreachable', async () => {
    const server = fakeServer({ f: flag(true) });
    client = new FlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch, streaming: false });
    await client.identify({ key: 'u' });

    (server.fetch as unknown as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('network down'));
    const errors: Error[] = [];
    client.on('error', (e) => errors.push(e));
    await client.identify({ key: 'u2' });

    expect(errors.map((e) => e.message)).toEqual(['network down']);
    expect(client.boolVariation('f', false)).toBe(true);
  });

  it('applies pushed changes and reports exactly which flags changed', async () => {
    const server = fakeServer({ a: flag(true), b: flag('x') });
    client = new FlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch });
    const changes: string[][] = [];
    client.on('change', (e) => changes.push(e.changed));
    await client.identify({ key: 'u' });
    await tick();

    server.change({ a: flag(false, 1, 'OFF'), b: flag('x') });
    await tick();

    expect(client.boolVariation('a', true)).toBe(false);
    expect(changes).toEqual([['a']]);
  });

  it('coalesces bursts of change events into at most one follow-up request', async () => {
    const server = fakeServer({ a: flag(1) });
    client = new FlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch });
    await client.identify({ key: 'u' });
    await tick();
    const before = server.evaluations();

    const release = server.hold();
    for (let i = 2; i <= 6; i++) server.change({ a: flag(i) });
    await tick();
    release();
    await tick();

    expect(server.evaluations() - before).toBeLessThanOrEqual(2);
    expect(client.numberVariation('a', 0)).toBe(6);
  });

  it('reconnects with backoff after the stream drops', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout'] });
    try {
      const server = fakeServer({ a: flag(true) });
      server.failNextStreams(1);
      client = new FlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch, reconnectDelayMs: 100 });
      client.on('error', () => {});
      await client.identify({ key: 'u' });
      // Let the failed first attempt settle (its error handling spans several microtask hops).
      await vi.waitFor(() => expect(server.streams()).toBe(1));

      // Full jitter: the first retry happens within reconnectDelayMs of virtual time.
      for (let elapsed = 0; elapsed <= 100 && server.streams() < 2; elapsed += 5) {
        await vi.advanceTimersByTimeAsync(5);
      }
      expect(server.streams()).toBe(2);
    } finally {
      vi.useRealTimers();
    }
  });
});
