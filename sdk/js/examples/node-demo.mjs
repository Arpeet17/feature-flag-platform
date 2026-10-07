// Live demo: watches flags for one user and prints every change pushed by the platform.
//   cd sdk/js && npm run build && node examples/node-demo.mjs
// Then toggle a flag in the dashboard (Production) and watch the update arrive instantly.
import { FlagClient } from '../dist/index.js';

const client = new FlagClient({
  baseUrl: process.env.FLAGS_URL ?? 'http://localhost:8080',
  sdkKey: process.env.FLAGS_SDK_KEY ?? 'sdk-demo-production-00000000000000000000000',
  logger: console,
});

await client.identify({ key: 'user-7', attributes: { email: 'sam@example.com', country: 'IN', plan: 'pro' } });

function show() {
  console.table({
    'new-checkout': client.boolVariation('new-checkout', false),
    'payments-enabled': client.boolVariation('payments-enabled', true),
    'search-algorithm': client.stringVariation('search-algorithm', 'bm25'),
    'max-upload-size-mb': client.numberVariation('max-upload-size-mb', 10),
  });
}

show();
client.on('change', ({ changed }) => {
  console.log(`${new Date().toISOString()} changed: ${changed.join(', ')}`);
  show();
});
process.on('SIGINT', () => {
  client.close();
  process.exit(0);
});
