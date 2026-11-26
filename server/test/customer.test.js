import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createApp } from '../src/app.js';
import { createSigner } from '../src/signer.js';
import { MemoryStore } from '../src/store.js';
import { TestPayments } from '../src/payments.js';
import { checkSecret } from '../src/secrets.js';

const keys = JSON.parse(readFileSync(new URL('../test-keys.json', import.meta.url)));
const BASE = 'http://server.test';
const details = { name: 'Dana Levi', phone: '+1 (212) 555-0100', email: 'Dana@Example.com', removalCode: 'secret-42' };

async function setup() {
  const store = new MemoryStore();
  const app = createApp({ store, signer: await createSigner(keys.privateJwk), payments: new TestPayments(), adminToken: 'admin-secret', baseUrl: BASE });
  const post = (path, body, token) => app.handle(new Request(BASE + path, {
    method: 'POST', body: JSON.stringify(body),
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
  }));
  const a = await (await post('/v1/activate', { installId: 'install-0123456789abcdef' })).json();
  return { store, app, post, a };
}

test('the phone sends the customer details; the removal code is kept only as a hash', async () => {
  const { store, post, a } = await setup();
  const r = await post('/v1/customer', { customer: details }, a.token);
  assert.equal(r.status, 200);
  const d = await store.getDevice(a.deviceId);
  assert.deepEqual([d.customer.name, d.customer.phone, d.customer.email], ['Dana Levi', '+1 (212) 555-0100', 'dana@example.com']);
  assert.ok(!JSON.stringify(d).includes('secret-42'), 'the plain removal code is never stored');
  assert.ok(await checkSecret('secret-42', d.removalCodeHash));
  assert.equal(await checkSecret('secret-43', d.removalCodeHash), false);
});

test('the removal code is set once; sending again does not replace it', async () => {
  const { store, post, a } = await setup();
  await post('/v1/customer', { customer: details }, a.token);
  const again = await (await post('/v1/customer', { customer: { ...details, removalCode: 'other-code' } }, a.token)).json();
  assert.equal(again.already, true);
  assert.ok(await checkSecret('secret-42', (await store.getDevice(a.deviceId)).removalCodeHash));
});

test('bad details and unknown phones are refused', async () => {
  const { post, a } = await setup();
  assert.equal((await post('/v1/customer', { customer: details })).status, 401);
  for (const bad of [{ ...details, email: 'nope' }, { ...details, removalCode: '12345' }, { ...details, phone: '12' }, { ...details, name: 'D' }, null]) {
    assert.equal((await post('/v1/customer', { customer: bad }, a.token)).status, 400);
  }
});

test('the staff device list never shows the removal code hash', async () => {
  const { app, post, a } = await setup();
  await post('/v1/customer', { customer: details }, a.token);
  const list = await (await app.handle(new Request(BASE + '/v1/admin/devices', { headers: { authorization: 'Bearer admin-secret' } }))).json();
  assert.equal(list.devices[0].hasRemovalCode, true);
  assert.ok(!JSON.stringify(list).includes('pbkdf2'));
});
