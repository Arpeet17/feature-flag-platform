import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Ruleset } from './engine/model.js';
import { LocalFlagClient } from './local-client.js';

const flag = (key: string, enabled: boolean, extra: object = {}) => ({
  key, type: 'BOOLEAN', enabled, salt: 's', version: 1,
  variations: [{ value: true }, { value: false }],
  targeting: { offVariation: 1, prerequisites: [], targets: [], rules: [], fallthrough: { variation: 0 }, ...extra },
});

/** Fake platform serving /rules with ETags, and an SSE stream the test can push into. */
function fakeServer(initial: Ruleset) {
  let ruleset = initial;
  let push: ((event: string) => void) | null = null;
  const requests: { url: string; ifNoneMatch: string | null }[] = [];
  let failRules = false;

  const fetchImpl = vi.fn(async (url: string, init: RequestInit = {}) => {
    const headers = new Headers(init.headers);
    requests.push({ url, ifNoneMatch: headers.get('If-None-Match') });
    if (url.endsWith('/rules')) {
      if (failRules) throw new Error('network down');
      const etag = `"${ruleset.version}"`;
      if (headers.get('If-None-Match') === etag) return new Response(null, { status: 304 });
      return new Response(JSON.stringify(ruleset), { status: 200, headers: { ETag: etag } });
    }
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        push = (e) => controller.enqueue(encoder.encode(e));
        push(`event:ready\ndata:{"version":${ruleset.version}}\n\n`);
      },
    });
    return new Response(body, { status: 200 });
  });

  return {
    fetch: fetchImpl as unknown as typeof fetch,
    rulesRequests: () => requests.filter((r) => r.url.endsWith('/rules')),
    publish(next: Ruleset) {
      ruleset = next;
      push?.(`event:flags-changed\ndata:{"version":${next.version}}\n\n`);
    },
    notifyWithoutChange() {
      push?.(`event:flags-changed\ndata:{"version":${ruleset.version}}\n\n`);
    },
    goOffline() {
      failRules = true;
    },
  };
}

const tick = () => new Promise((r) => setTimeout(r, 15));

describe('LocalFlagClient', () => {
  let client: LocalFlagClient | undefined;
  afterEach(() => client?.close());

  it('evaluates any number of users locally after one download', async () => {
    const server = fakeServer({
      version: 1,
      segments: {},
      flags: {
        'pro-only': flag('pro-only', true, {
          rules: [{ id: 'r', clauses: [{ attribute: 'plan', operator: 'IN', values: ['pro'], negate: false }], serve: { variation: 0 } }],
          fallthrough: { variation: 1 },
        }) as never,
      },
    });
    client = new LocalFlagClient({ baseUrl: 'https://flags.test', sdkKey: 'sdk-1', fetch: server.fetch, streaming: false });
    await client.start();

    expect(client.boolVariation('pro-only', { key: 'a', attributes: { plan: 'pro' } }, false)).toBe(true);
    expect(client.boolVariation('pro-only', { key: 'b', attributes: { plan: 'free' } }, true)).toBe(false);
    expect(client.variationDetail('pro-only', { key: 'c', attributes: { plan: 'pro' } }, false).reason)
      .toEqual({ kind: 'RULE_MATCH', ruleIndex: 0, ruleId: 'r' });
    expect(client.allFlags({ key: 'd', attributes: { plan: 'pro' } })).toEqual({ 'pro-only': true });
    expect(server.rulesRequests()).toHaveLength(1);
  });

  it('falls back on unknown flags and wrong types without throwing', async () => {
    const server = fakeServer({ version: 1, segments: {}, flags: { on: flag('on', true) as never } });
    client = new LocalFlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch, streaming: false });
    await client.start();
    expect(client.boolVariation('missing', { key: 'u' }, true)).toBe(true);
    expect(client.variationDetail('missing', { key: 'u' }, true).reason.errorKind).toBe('FLAG_NOT_FOUND');
    expect(client.stringVariation('on', { key: 'u' }, 'dflt')).toBe('dflt');
    expect(client.variationDetail('on', { key: 'u' }, 'dflt').reason.errorKind).toBe('WRONG_TYPE');
  });

  it('refreshes on change notifications, reporting the changed flags', async () => {
    const server = fakeServer({ version: 1, segments: {}, flags: { a: flag('a', true) as never, b: flag('b', true) as never } });
    client = new LocalFlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch });
    const changes: string[][] = [];
    client.on('change', (e) => changes.push(e.changed));
    await client.start();
    await tick();

    server.publish({ version: 2, segments: {}, flags: { a: flag('a', false) as never, b: flag('b', true) as never } });
    await tick();

    expect(client.boolVariation('a', { key: 'u' }, true)).toBe(false);
    expect(client.rulesetVersion).toBe(2);
    expect(changes).toEqual([['a']]);
  });

  it('uses conditional requests so unchanged rulesets cost a 304', async () => {
    const server = fakeServer({ version: 7, segments: {}, flags: { a: flag('a', true) as never } });
    client = new LocalFlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch });
    const changes = vi.fn();
    client.on('change', changes);
    await client.start();
    await tick();

    server.notifyWithoutChange();
    await tick();

    const rules = server.rulesRequests();
    expect(rules.at(-1)?.ifNoneMatch).toBe('"7"');
    expect(changes).not.toHaveBeenCalled();
  });

  it('keeps serving the last ruleset when the platform is unreachable', async () => {
    const server = fakeServer({ version: 1, segments: {}, flags: { a: flag('a', true) as never } });
    client = new LocalFlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch });
    const errors: Error[] = [];
    client.on('error', (e) => errors.push(e));
    await client.start();
    await tick();

    server.goOffline();
    server.notifyWithoutChange();
    await tick();

    expect(errors.map((e) => e.message)).toContain('network down');
    expect(client.boolVariation('a', { key: 'u' }, false)).toBe(true);
  });

  it('start() rejects when the first download fails', async () => {
    const server = fakeServer({ version: 1, segments: {}, flags: {} });
    server.goOffline();
    client = new LocalFlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch, streaming: false });
    await expect(client.start()).rejects.toThrow('network down');
    expect(client.isReady()).toBe(false);
    expect(client.boolVariation('a', { key: 'u' }, true)).toBe(true);
  });
});

describe('variationDetail with a null fallback', () => {
  it('accepts any variation type', async () => {
    const server = fakeServer({ version: 1, segments: {}, flags: { on: flag('on', true) as never } });
    const client = new LocalFlagClient({ baseUrl: 'https://flags.test', sdkKey: 'k', fetch: server.fetch, streaming: false });
    await client.start();
    expect(client.variationDetail('on', { key: 'u' }, null)).toEqual({ value: true, variation: 0, reason: { kind: 'FALLTHROUGH' } });
    client.close();
  });
});
