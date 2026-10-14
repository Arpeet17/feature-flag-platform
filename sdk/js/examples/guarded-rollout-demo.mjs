// Live guarded-rollout demo against a running platform (demo data enabled).
//
//   node examples/guarded-rollout-demo.mjs          # healthy release: ramps 10 → 25 → 50 → 100%
//   node examples/guarded-rollout-demo.mjs --bug    # buggy release: automatically rolled back
//
// It creates a fresh flag, starts a guarded rollout (15s steps, guardrail "error"), then plays
// users through a LocalFlagClient: every user evaluates the flag locally, and users on the new
// variation hit errors at a higher rate when --bug is set. The platform watches the error rate
// per step and decides on its own; this script only reports what it sees.
import { LocalFlagClient } from '../dist/index.js';

const baseUrl = process.env.FLAGS_URL ?? 'http://localhost:8080';
const sdkKey = process.env.FLAGS_SDK_KEY ?? 'sdk-demo-production-00000000000000000000000';
const bug = process.argv.includes('--bug');
const project = 'demo-app';
const flagKey = `recommendations-v2-${Date.now().toString(36)}`;
const usersPerSecond = 250;
const baseErrorRate = 0.02;
const buggyErrorRate = 0.08;

async function api(path, init = {}, token) {
  const res = await fetch(`${baseUrl}/api/v1${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  });
  const body = res.status === 204 ? null : await res.json();
  if (!res.ok) throw new Error(`${init.method ?? 'GET'} ${path} → ${res.status}: ${JSON.stringify(body)}`);
  return body;
}

const { accessToken } = await api('/auth/login', { method: 'POST', body: JSON.stringify({ email: 'admin@ffp.local', password: 'admin123' }) });
await api(`/projects/${project}/flags`, { method: 'POST', body: JSON.stringify({
  key: flagKey, name: 'Recommendations v2', type: 'BOOLEAN', tags: ['demo', 'rollout'],
}) }, accessToken);
const rollout = await api(`/projects/${project}/flags/${flagKey}/environments/production/rollouts`, { method: 'POST', body: JSON.stringify({
  treatmentVariation: 0, controlVariation: 1, steps: [10, 25, 50, 100], stepIntervalSeconds: 15, minUsersPerStep: 50, guardrailEventKey: 'error',
}) }, accessToken);
console.log(`Flag ${flagKey}: guarded rollout #${rollout.id} started (${bug ? 'BUGGY' : 'healthy'} release, ${buggyErrorRate * 100}% vs ${baseErrorRate * 100}% errors when buggy)`);
console.log(`Dashboard: http://localhost:5173/projects/${project}/rollouts/${rollout.id}\n`);

const client = new LocalFlagClient({ baseUrl, sdkKey, flushIntervalMs: 1_000 });
await client.start();

let n = 0;
const traffic = setInterval(() => {
  for (let i = 0; i < usersPerSecond / 10; i++) {
    const user = { key: `visitor-${n++}` };
    const newVersion = client.boolVariation(flagKey, user, false);
    const errorRate = newVersion && bug ? buggyErrorRate : baseErrorRate;
    if (Math.random() < errorRate) client.track('error', user);
  }
}, 100);

const started = Date.now();
let lastLine = '';
for (;;) {
  await new Promise((r) => setTimeout(r, 2_000));
  const r = await api(`/projects/${project}/rollouts/${rollout.id}`, {}, accessToken);
  const g = r.guardrail;
  const rate = (v) => g?.arms.find((a) => a.variation === v);
  const fmt = (a) => (a ? `${(a.estimate * 100).toFixed(1)}% of ${a.users}` : '—');
  const line = `[${String(Math.round((Date.now() - started) / 1000)).padStart(3)}s] ${r.status.padEnd(11)} ${String(r.currentPercentage).padStart(3)}%`
    + (g ? `  errors: new ${fmt(rate(0))} | old ${fmt(rate(1))}` : '');
  if (line !== lastLine) console.log(line);
  lastLine = line;
  if (r.status !== 'RUNNING') {
    console.log(`\n${r.status === 'ROLLED_BACK' ? '↺' : '✓'} ${r.lastDecision}`);
    console.log(`Every user now gets: ${client.boolVariation(flagKey, { key: 'someone-new' }, false) ? 'new version' : 'old version'}`);
    break;
  }
  if (Date.now() - started > 180_000) {
    console.log('Timed out waiting for a decision');
    break;
  }
}
clearInterval(traffic);
await client.close();
