import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createApp } from '../src/app.js';
import { createSigner } from '../src/signer.js';
import { MemoryStore } from '../src/store.js';
import { TestPayments } from '../src/payments.js';
import { OutboxMailer } from '../src/services.js';

const keys = JSON.parse(readFileSync(new URL('../test-keys.json', import.meta.url)));
const BASE = 'http://server.test';

async function setup() {
  const store = new MemoryStore();
  const mailer = new OutboxMailer();
  const app = createApp({ store, signer: await createSigner(keys.privateJwk), payments: new TestPayments(), adminToken: 'a', baseUrl: BASE, ownerPassword: 'owner-password-1', mailer });
  const call = (path, init = {}) => app.handle(new Request(BASE + path, { redirect: 'manual', ...init }));
  const form = (path, body, cookie = '') => call(path, { method: 'POST', body: new URLSearchParams(body).toString(), headers: { 'content-type': 'application/x-www-form-urlencoded', cookie, origin: BASE } });
  const cookieOf = (r) => (r.headers.get('set-cookie') ?? '').split(';')[0];
  const owner = cookieOf(await form('/login', { username: 'owner', password: 'owner-password-1' }));
  await form('/owner/staff', { username: 'dana', password: 'staff-password-1' }, owner);
  const staff = cookieOf(await form('/login', { username: 'dana', password: 'staff-password-1' }));
  const get = (path, cookie = staff) => call(path, { headers: { cookie } });
  const json = (path, body, token) => call(path, { method: 'POST', body: JSON.stringify(body), headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) } });
  const a = await (await json('/v1/activate', { installId: 'install-0123456789abcdef' })).json();
  await json('/v1/customer', { customer: { name: 'Eli Cohen', phone: '(718) 555-9999', email: 'eli@example.com', removalCode: 'eli-secret-1' } }, a.token);
  return { store, mailer, form, get, staff, a };
}

test('staff search by name, phone or email and open the customer', async () => {
  const { get, a } = await setup();
  for (const q of ['cohen', '718555', 'ELI@example']) assert.match(await (await get('/staff?q=' + encodeURIComponent(q))).text(), /Eli Cohen/);
  assert.match(await (await get('/staff?q=zz')).text(), /at least 3/);
  assert.match(await (await get('/staff?q=nobody')).text(), /No customer found/);
  const page = await (await get(`/staff/customers/${a.deviceId}`)).text();
  assert.match(page, /Remove the filter/);
  assert.doesNotMatch(page, /Mark paid|Lock again|without the code|eli-secret-1/);
});

test("staff remove the filter only with the customer's code", async () => {
  const { store, form, staff, a } = await setup();
  const wrong = await form(`/staff/customers/${a.deviceId}/remove`, { code: 'guess-123', reason: 'Customer called' }, staff);
  assert.match(decodeURIComponent(wrong.headers.get('location')), /wrong/);
  assert.equal((await store.getDevice(a.deviceId)).status, 'unpaid');
  const ok = await form(`/staff/customers/${a.deviceId}/remove`, { code: 'eli-secret-1', reason: 'Customer called' }, staff);
  assert.match(decodeURIComponent(ok.headers.get('location')), /Done/);
  assert.equal((await store.getDevice(a.deviceId)).status, 'removed');
  assert.equal((await store.listAudit()).at(-1).by, 'dana');
});

test('staff send a reset email; staff have no owner actions', async () => {
  const { store, mailer, form, get, staff, a } = await setup();
  await form(`/staff/customers/${a.deviceId}/reset`, {}, staff);
  assert.equal(mailer.sent.at(-1).to, 'eli@example.com');
  assert.equal((await form(`/owner/customers/${a.deviceId}/paid`, { reason: 'x' }, staff)).headers.get('location'), '/staff');
  assert.equal((await store.getDevice(a.deviceId)).status, 'unpaid');
  assert.equal((await get('/staff/customers/dev_nope')).status, 404);
});
