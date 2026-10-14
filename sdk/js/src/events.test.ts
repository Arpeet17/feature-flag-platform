import { describe, expect, it, vi } from 'vitest';
import { EventProcessor } from './events.js';
import { resolveOptions } from './internal.js';
import { LocalFlagClient } from './local-client.js';

function capture(status = 202) {
  const batches: { events: Record<string, unknown>[] }[] = [];
  const fetchImpl = vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url.endsWith('/events')) {
      batches.push(JSON.parse(String(init.body)));
      return new Response('{}', { status });
    }
    return new Response(JSON.stringify({
      version: 1, segments: {},
      flags: {
        exp: { key: 'exp', type: 'BOOLEAN', enabled: true, salt: 's', version: 1, variations: [{ value: true }, { value: false }],
          targeting: { offVariation: 1, prerequisites: [], targets: [], rules: [],
            fallthrough: { rollout: { variations: [{ variation: 0, weight: 50_000 }, { variation: 1, weight: 50_000 }] } } } },
      },
    }), { status: 200 });
  });
  return { fetch: fetchImpl as unknown as typeof fetch, batches, all: () => batches.flatMap((b) => b.events) };
}

const processor = (fetchImpl: typeof fetch, maxQueueSize = 10_000) =>
  new EventProcessor(resolveOptions({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: fetchImpl }),
    { sendEvents: true, flushIntervalMs: 60_000, maxQueueSize }, () => {});

describe('EventProcessor', () => {
  it('deduplicates exposures per flag, user and variation', async () => {
    const server = capture();
    const p = processor(server.fetch);
    for (let i = 0; i < 100; i++) p.exposure('exp', 'u1', 0, true);
    p.exposure('exp', 'u2', 0, true);
    p.exposure('exp', 'u1', 1, true); // variation changed: a new exposure
    await p.close();
    expect(server.all().map((e) => `${e.contextKey}:${e.variation}`)).toEqual(['u1:0', 'u2:0', 'u1:1']);
  });

  it('splits large queues into batches the server accepts', async () => {
    const server = capture();
    const p = processor(server.fetch);
    for (let i = 0; i < 2_500; i++) p.track('click', `u${i}`);
    await p.close();
    expect(server.batches.map((b) => b.events.length)).toEqual([1000, 1000, 500]);
  });

  it('re-queues on server errors and delivers on the next flush', async () => {
    const server = capture(503);
    const p = processor(server.fetch);
    p.track('purchase', 'u1', 49.5);
    await p.flush();
    expect(p.pending).toBe(1);
    (server.fetch as unknown as ReturnType<typeof vi.fn>).mockImplementation(async () => new Response('{}', { status: 202 }));
    await p.flush();
    expect(p.pending).toBe(0);
  });

  it('drops the oldest events when the queue is full', async () => {
    const server = capture(503);
    const p = processor(server.fetch, 3);
    for (let i = 0; i < 5; i++) p.track('e', `u${i}`);
    expect(p.pending).toBe(3);
    expect(p.dropped).toBe(2);
  });

  it('does not retry batches the server rejects as invalid', async () => {
    const server = capture(400);
    const p = processor(server.fetch);
    p.track('e', 'u');
    await p.flush();
    expect(p.pending).toBe(0);
    expect(p.dropped).toBe(1);
  });
});

describe('client integration', () => {
  it('records exposures on flag reads (not on allFlags) and custom events via track()', async () => {
    const server = capture();
    const client = new LocalFlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch, streaming: false });
    await client.start();
    client.allFlags({ key: 'bootstrap-only' });
    const v = client.variationDetail('exp', { key: 'alice' }, false);
    client.boolVariation('exp', { key: 'alice' }, false);
    client.track('purchase', { key: 'alice' }, 20);
    await client.close();

    expect(server.all()).toEqual([
      expect.objectContaining({ kind: 'exposure', contextKey: 'alice', flagKey: 'exp', variation: v.variation, inExperiment: true }),
      expect.objectContaining({ kind: 'custom', contextKey: 'alice', eventKey: 'purchase', value: 20 }),
    ]);
  });

  it('sends nothing when events are disabled', async () => {
    const server = capture();
    const client = new LocalFlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch, streaming: false, sendEvents: false });
    await client.start();
    client.boolVariation('exp', { key: 'a' }, false);
    client.track('purchase', { key: 'a' });
    await client.close();
    expect(server.batches).toHaveLength(0);
  });
});
