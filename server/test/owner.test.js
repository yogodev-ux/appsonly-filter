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
  const app = createApp({ store, signer: await createSigner(keys.privateJwk), payments: new TestPayments(), adminToken: 'admin-secret', baseUrl: BASE, ownerPassword: 'owner-password-1', mailer });
  const call = (path, init = {}) => app.handle(new Request(BASE + path, { redirect: 'manual', ...init }));
  const form = (path, body, cookie = '', origin = BASE) => call(path, {
    method: 'POST', body: new URLSearchParams(body).toString(),
    headers: { 'content-type': 'application/x-www-form-urlencoded', cookie, origin },
  });
  const signIn = async (username, password) => {
    const r = await form('/login', { username, password });
    return { r, cookie: (r.headers.get('set-cookie') ?? '').split(';')[0] };
  };
  const get = async (path, cookie) => call(path, { headers: { cookie } });
  const phone = async (n = 1) => {
    const json = (path, body, token) => call(path, { method: 'POST', body: JSON.stringify(body), headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) } });
    const a = await (await json('/v1/activate', { installId: `install-0123456789abcd-${n}`, model: 'Pixel 7' })).json();
    await json('/v1/customer', { customer: { name: `Customer ${n}`, phone: `+1 212 555 010${n}`, email: `c${n}@example.com`, removalCode: `code-${n}-secret` } }, a.token);
    return a;
  };
  return { store, mailer, call, form, signIn, get, phone };
}

test('one sign-in page: the owner lands in the owner portal, staff in the staff portal, and neither can open the other', async () => {
  const { form, signIn, get } = await setup();
  assert.match(await (await get('/login')).text(), /Apps Only sign-in/);
  const o = await signIn('owner', 'owner-password-1');
  assert.equal(o.r.headers.get('location'), '/owner');
  await form('/owner/staff', { username: 'dana', password: 'staff-password-1' }, o.cookie);
  const s = await signIn('dana', 'staff-password-1');
  assert.equal(s.r.headers.get('location'), '/staff');
  assert.equal((await get('/owner', s.cookie)).headers.get('location'), '/staff');
  assert.equal((await get('/owner/customers', s.cookie)).headers.get('location'), '/staff');
  assert.equal((await get('/staff', o.cookie)).headers.get('location'), '/owner');
  assert.equal((await get('/owner', '')).headers.get('location'), '/login');
  assert.equal((await signIn('owner', 'wrong')).r.status, 401);
});

test('the owner portal opens on alerts; marking one handled clears it', async () => {
  const { store, call, form, signIn, get, phone } = await setup();
  const a = await phone(1);
  await call('/v1/tamper', { method: 'POST', body: JSON.stringify({ events: [{ type: 'kiosk_mode_exited' }] }), headers: { 'content-type': 'application/json', authorization: `Bearer ${a.token}` } });
  const { cookie } = await signIn('owner', 'owner-password-1');
  const home = await get('/owner', cookie);
  assert.equal(home.headers.get('content-security-policy').includes("default-src 'none'"), true);
  const text = await home.text();
  assert.match(text, /Alerts that need you/);
  assert.match(text, /Tamper attempt/);
  assert.match(text, /Customer 1/);
  const id = (await store.listAlerts())[0].id;
  await form(`/owner/alerts/${id}/handled`, {}, cookie);
  assert.match(await (await get('/owner', cookie)).text(), /Nothing needs you right now/);
});

test('customers: search, details, mark paid, lock again, remove without the code (phone code must be typed)', async () => {
  const { store, form, signIn, get, phone } = await setup();
  const a = await phone(1);
  await phone(2);
  const { cookie } = await signIn('owner', 'owner-password-1');
  const list = await (await get('/owner/customers?q=c2%40example', cookie)).text();
  assert.match(list, /Customer 2/);
  assert.doesNotMatch(list, /Customer 1/);
  const page = await (await get(`/owner/customers/${a.deviceId}`, cookie)).text();
  assert.match(page, /c1@example.com/);
  assert.doesNotMatch(page, /code-1-secret/);
  const r = await form(`/owner/customers/${a.deviceId}/paid`, { reason: 'Paid in cash' }, cookie);
  assert.match(r.headers.get('location'), /ok=/);
  assert.equal((await store.getDevice(a.deviceId)).status, 'paid');
  await form(`/owner/customers/${a.deviceId}/lock`, { reason: 'Refund' }, cookie);
  assert.equal((await store.getDevice(a.deviceId)).status, 'unpaid');
  const wrong = await form(`/owner/customers/${a.deviceId}/remove`, { reason: 'ID checked', confirm: 'AOS-NOPE-NOPE' }, cookie);
  assert.match(decodeURIComponent(wrong.headers.get('location')), /doesn't match/);
  assert.equal((await store.getDevice(a.deviceId)).status, 'unpaid');
  await form(`/owner/customers/${a.deviceId}/remove`, { reason: 'ID checked', confirm: a.payCode.toLowerCase() }, cookie);
  assert.equal((await store.getDevice(a.deviceId)).status, 'removed');
  const log = await (await get('/owner/actions', cookie)).text();
  assert.match(log, /Removed the filter without the code/);
  assert.match(log, /ID checked/);
  assert.match(await (await get('/owner/sales', cookie)).text(), /Paid in total<\/span><b>1/);
});

test('staff accounts: add, turn off (signs them out), set a new password', async () => {
  const { form, signIn, get } = await setup();
  const { cookie } = await signIn('owner', 'owner-password-1');
  assert.match(decodeURIComponent((await form('/owner/staff', { username: 'dana', password: 'short' }, cookie)).headers.get('location')), /10 characters/);
  await form('/owner/staff', { username: 'dana', password: 'staff-password-1' }, cookie);
  assert.match(decodeURIComponent((await form('/owner/staff', { username: 'dana', password: 'staff-password-1' }, cookie)).headers.get('location')), /taken/);
  const s = await signIn('dana', 'staff-password-1');
  await form('/owner/staff/dana/off', {}, cookie);
  assert.equal((await get('/staff', s.cookie)).headers.get('location'), '/login');
  await form('/owner/staff/dana/on', {}, cookie);
  await form('/owner/staff/dana/password', { password: 'another-password-2' }, cookie);
  assert.equal((await signIn('dana', 'staff-password-1')).r.status, 401);
  assert.equal((await signIn('dana', 'another-password-2')).r.status, 303);
});

test('setup logs show; posts from another site are refused; sign-out ends the session', async () => {
  const { call, form, signIn, get } = await setup();
  await call('/v1/installer-logs', { method: 'POST', body: 'STEP Checking\nERROR No phone found\nRESULT failed' });
  const { cookie } = await signIn('owner', 'owner-password-1');
  const logs = await (await get('/owner/setup-logs', cookie)).text();
  assert.match(logs, /No phone found/);
  assert.equal((await form('/owner/staff', { username: 'evil', password: 'evil-password-1' }, cookie, 'https://evil.example')).status, 403);
  await form('/logout', {}, cookie);
  assert.equal((await get('/owner', cookie)).headers.get('location'), '/login');
});
