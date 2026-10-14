// Live differential test: evaluates every flag for N random users both locally (SDK engine)
// and remotely (server), and reports any disagreement. Exit code 1 on mismatch.
//   node examples/differential-check.mjs [users=10000]
import { LocalFlagClient } from '../dist/index.js';

const baseUrl = process.env.FLAGS_URL ?? 'http://localhost:8080';
const sdkKey = process.env.FLAGS_SDK_KEY ?? 'sdk-demo-production-00000000000000000000000';
const users = Number(process.argv[2] ?? 10_000);
const pick = (xs) => xs[Math.floor(Math.random() * xs.length)];

const client = new LocalFlagClient({ baseUrl, sdkKey, streaming: false });
await client.start();

let mismatches = 0;
let comparisons = 0;
const batch = 200;
for (let start = 0; start < users; start += batch) {
  const contexts = Array.from({ length: Math.min(batch, users - start) }, (_, i) => ({
    key: `user-${start + i}-${Math.random().toString(36).slice(2, 7)}`,
    attributes: {
      email: `u${start + i}@${pick(['acme.com', 'example.com', 'gmail.com'])}`,
      country: pick(['US', 'IN', 'DE', 'BR']),
      plan: pick(['free', 'pro', 'enterprise']),
      appVersion: pick(['2.1.0', '2.2.9', '2.3.0', '2.3.0-beta.1', '2.10.4', 'garbage']),
    },
  }));
  await Promise.all(contexts.map(async (context) => {
    const res = await fetch(`${baseUrl}/api/v1/sdk/evaluate`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: sdkKey },
      body: JSON.stringify({ context }),
    });
    const remote = (await res.json()).flags;
    for (const [key, r] of Object.entries(remote)) {
      const local = client.variationDetail(key, context, null);
      comparisons++;
      if (local.variation !== (r.variation ?? null) || JSON.stringify(local.reason) !== JSON.stringify(r.reason)) {
        if (mismatches++ < 5) console.log('MISMATCH', key, JSON.stringify(context), { local, remote: r });
      }
    }
  }));
}

// Local evaluation cost: all flags for one user, many times.
const flagKeys = Object.keys(client.allFlags({ key: 'warmup' }));
const ctx = { key: 'bench-user', attributes: { email: 'x@acme.com', plan: 'pro', country: 'IN', appVersion: '2.4.0' } };
for (let i = 0; i < 20_000; i++) client.allFlags(ctx);
const N = 200_000;
const t0 = process.hrtime.bigint();
for (let i = 0; i < N; i++) client.boolVariation('new-checkout', { key: `u${i}`, attributes: ctx.attributes }, false);
const perEval = Number(process.hrtime.bigint() - t0) / N;

console.log(`${comparisons.toLocaleString()} flag evaluations for ${users.toLocaleString()} users: ${mismatches} mismatches`);
console.log(`local evaluation: ${(perEval / 1000).toFixed(2)} µs per flag (${flagKeys.length} flags in ruleset)`);
client.close();
process.exit(mismatches === 0 ? 0 : 1);
