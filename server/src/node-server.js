// Runs the server on plain Node (local testing and CI; any small VPS works too).
// Settings come from environment variables:
//   PORT (8787)  BASE_URL  DATA_FILE (data.json)  ADMIN_TOKEN
//   OWNER_PASSWORD (sign-in for the owner portal, user name "owner")
//   LICENSE_PRIVATE_JWK (JSON; default: the committed TEST key)
//   PAYMENTS=test|stripe  STRIPE_SECRET_KEY  STRIPE_WEBHOOK_SECRET  PRICE_CENTS  CURRENCY  PRICE_TEXT
import http from 'node:http';
import { readFileSync } from 'node:fs';
import { createApp } from './app.js';
import { createSigner } from './signer.js';
import { FileStore } from './store.js';
import { StripePayments, TestPayments } from './payments.js';

const env = process.env;
const port = Number(env.PORT ?? 8787);
const baseUrl = env.BASE_URL ?? `http://localhost:${port}`;
const testKeys = JSON.parse(readFileSync(new URL('../test-keys.json', import.meta.url)));
const privateJwk = env.LICENSE_PRIVATE_JWK ? JSON.parse(env.LICENSE_PRIVATE_JWK) : testKeys.privateJwk;
if (!env.LICENSE_PRIVATE_JWK) console.warn('Using the TEST signing key. Set LICENSE_PRIVATE_JWK for real use.');

const payments = env.PAYMENTS === 'stripe'
  ? new StripePayments({
      secretKey: env.STRIPE_SECRET_KEY,
      webhookSecret: env.STRIPE_WEBHOOK_SECRET,
      priceCents: Number(env.PRICE_CENTS ?? 0),
      currency: env.CURRENCY ?? 'usd',
      productName: 'Apps Only phone setup',
    })
  : new TestPayments();

if (!env.OWNER_PASSWORD) console.warn('OWNER_PASSWORD is not set, so nobody can sign in as the owner.');
const app = createApp({
  store: new FileStore(env.DATA_FILE ?? 'data.json'),
  signer: await createSigner(privateJwk),
  payments,
  adminToken: env.ADMIN_TOKEN ?? '',
  baseUrl,
  priceText: env.PRICE_TEXT ?? '',
  ownerPassword: env.OWNER_PASSWORD ?? '',
});

http
  .createServer(async (req, res) => {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = chunks.length ? Buffer.concat(chunks) : undefined;
    const request = new Request(new URL(req.url, baseUrl), {
      method: req.method,
      headers: req.headers,
      body: req.method === 'GET' || req.method === 'HEAD' ? undefined : body,
    });
    const response = await app.handle(request);
    res.writeHead(response.status, Object.fromEntries(response.headers));
    res.end(Buffer.from(await response.arrayBuffer()));
    console.log(`${req.method} ${req.url} -> ${response.status}`);
  })
  .listen(port, '0.0.0.0', () => console.log(`Apps Only server on ${baseUrl} (payments: ${payments.name})`));

// Phones set up but unpaid for 3 days become alerts; check every hour.
setInterval(() => app.services.checkUnpaid().catch((e) => console.error(e)), 3600 * 1000).unref();
