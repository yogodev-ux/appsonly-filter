import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createApp } from '../src/app.js';
import { createSigner, verifyLicense } from '../src/signer.js';
import { MemoryStore } from '../src/store.js';
import { StripePayments, TestPayments } from '../src/payments.js';

const keys = JSON.parse(readFileSync(new URL('../test-keys.json', import.meta.url)));
const BASE = 'http://server.test';

async function setup(payments = new TestPayments()) {
  const store = new MemoryStore();
  const app = createApp({ store, signer: await createSigner(keys.privateJwk), payments, adminToken: 'admin-secret', baseUrl: BASE });
  const call = (path, init = {}) => app.handle(new Request(BASE + path, init));
  const post = (path, body, headers = {}) =>
    call(path, { method: 'POST', body: typeof body === 'string' ? body : JSON.stringify(body), headers: { 'content-type': 'application/json', ...headers } });
  return { store, call, post };
}

const installId = 'install-0123456789abcdef';

test('a new phone is unpaid, with a signed license bound to it', async () => {
  const { post } = await setup();
  const r = await (await post('/v1/activate', { installId, model: 'Pixel 6', sdk: 33 })).json();
  assert.equal(r.status, 'unpaid');
  assert.match(r.payCode, /^AOS-[A-Z2-9]{4}-[A-Z2-9]{4}$/);
  const lic = await verifyLicense(r.license, keys.publicSpkiB64);
  assert.equal(lic.deviceId, r.deviceId);
  assert.equal(lic.status, 'unpaid');
});

test('a tampered license fails verification', async () => {
  const { post } = await setup();
  const r = await (await post('/v1/activate', { installId })).json();
  const [body, sig] = r.license.split('.');
  const fake = Buffer.from(JSON.stringify({ v: 1, deviceId: r.deviceId, status: 'paid', issuedAt: 1 })).toString('base64url');
  assert.equal(await verifyLicense(`${fake}.${sig}`, keys.publicSpkiB64), null);
  assert.ok(await verifyLicense(`${body}.${sig}`, keys.publicSpkiB64));
});

test('test payment marks the phone paid; the phone then gets a paid license', async () => {
  const { post, call } = await setup();
  const a = await (await post('/v1/activate', { installId })).json();
  const page = await (await call(`/pay/${a.payCode}`)).text();
  assert.match(page, /Pay now/);
  const pay = await post(`/pay/${a.payCode}/test`, '');
  assert.equal(pay.status, 303);
  const l = await (await call('/v1/license', { headers: { authorization: `Bearer ${a.token}` } })).json();
  assert.equal(l.status, 'paid');
  assert.equal((await verifyLicense(l.license, keys.publicSpkiB64)).status, 'paid');
  assert.match(await (await call(`/pay/${a.payCode}`)).text(), /Paid, thank you/);
});

test('activating again keeps the device but replaces the token', async () => {
  const { post, call } = await setup();
  const a = await (await post('/v1/activate', { installId })).json();
  const b = await (await post('/v1/activate', { installId })).json();
  assert.equal(a.deviceId, b.deviceId);
  assert.equal((await call('/v1/license', { headers: { authorization: `Bearer ${a.token}` } })).status, 401);
  assert.equal((await call('/v1/license', { headers: { authorization: `Bearer ${b.token}` } })).status, 200);
});

test('bad requests are refused', async () => {
  const { post, call } = await setup();
  assert.equal((await post('/v1/activate', { installId: 'short' })).status, 400);
  assert.equal((await call('/v1/license')).status, 401);
  assert.equal((await call('/v1/admin/devices')).status, 401);
  assert.equal((await call('/v1/admin/devices', { headers: { authorization: 'Bearer wrong' } })).status, 401);
  assert.equal((await call('/pay/AOS-XXXX-XXXX')).status, 404);
});

test('staff can list phones (without secrets) and change status', async () => {
  const { post, call } = await setup();
  const a = await (await post('/v1/activate', { installId })).json();
  const auth = { authorization: 'Bearer admin-secret' };
  const list = await (await call('/v1/admin/devices', { headers: auth })).json();
  assert.equal(list.devices.length, 1);
  assert.equal(list.devices[0].tokenHash, undefined);
  assert.equal(list.devices[0].installId, undefined);
  const r = await post(`/v1/admin/devices/${a.deviceId}/status`, { status: 'paid' }, auth);
  assert.equal((await r.json()).status, 'paid');
  assert.equal((await post(`/v1/admin/devices/${a.deviceId}/status`, { status: 'free' }, auth)).status, 400);
});

test('installer logs are stored', async () => {
  const { post, call } = await setup();
  assert.equal((await post('/v1/installer-logs', 'STEP Checking the phone', { 'content-type': 'text/plain' })).status, 200);
  const logs = await (await call('/v1/admin/installer-logs', { headers: { authorization: 'Bearer admin-secret' } })).json();
  assert.equal(logs.logs.length, 1);
});

test('stripe webhook: only a correctly signed, paid checkout marks the phone paid', async () => {
  const stripe = new StripePayments({ secretKey: 'sk_test_x', webhookSecret: 'whsec_test', priceCents: 100, currency: 'usd', productName: 'x' });
  const { post, store } = await setup(stripe);
  const a = await (await post('/v1/activate', { installId })).json();
  const event = JSON.stringify({ type: 'checkout.session.completed', data: { object: { client_reference_id: a.deviceId, payment_status: 'paid' } } });
  const t = Math.floor(Date.now() / 1000);
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode('whsec_test'), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const mac = Buffer.from(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(`${t}.${event}`))).toString('hex');
  assert.equal((await post('/v1/stripe/webhook', event, { 'stripe-signature': `t=${t},v1=${'0'.repeat(64)}` })).status, 400);
  assert.equal((await store.getDevice(a.deviceId)).status, 'unpaid');
  assert.equal((await post('/v1/stripe/webhook', event, { 'stripe-signature': `t=${t},v1=${mac}` })).status, 200);
  assert.equal((await store.getDevice(a.deviceId)).status, 'paid');
});
