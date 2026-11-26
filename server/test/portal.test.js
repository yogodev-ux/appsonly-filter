import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createApp } from '../src/app.js';
import { createSigner, verifyLicense } from '../src/signer.js';
import { MemoryStore } from '../src/store.js';
import { TestPayments } from '../src/payments.js';

const keys = JSON.parse(readFileSync(new URL('../test-keys.json', import.meta.url)));
const BASE = 'http://server.test';
const OWNER_PW = 'owner-password-123';

async function setup() {
  const store = new MemoryStore();
  const app = createApp({ store, signer: await createSigner(keys.privateJwk), payments: new TestPayments(), adminToken: 'admin-secret', baseUrl: BASE, ownerPassword: OWNER_PW });
  const call = (path, init = {}) => app.handle(new Request(BASE + path, { redirect: 'manual', ...init }));
  const form = (path, data, cookie = '', origin = BASE) =>
    call(path, { method: 'POST', body: new URLSearchParams(data).toString(), headers: { 'content-type': 'application/x-www-form-urlencoded', cookie, origin } });
  const login = async (username, password) => {
    const r = await form('/login', { username, password });
    const c = r.headers.get('set-cookie');
    return { r, cookie: c ? c.split(';')[0] : '' };
  };
  const phone = async (id = 'install-0123456789abcdef') => {
    const r = await call('/v1/activate', { method: 'POST', body: JSON.stringify({ installId: id, model: 'Pixel 6', sdk: 33 }), headers: { 'content-type': 'application/json' } });
    return r.json();
  };
  const license = async (token) => (await call('/v1/license', { headers: { authorization: `Bearer ${token}` } })).json();
  return { store, call, form, login, phone, license };
}

test('dashboard pages need a sign-in', async () => {
  const { call } = await setup();
  for (const p of ['/admin', '/admin/staff', '/admin/logs', '/admin/audit', '/support']) {
    const r = await call(p);
    assert.equal(r.status, 303, p);
    assert.match(r.headers.get('location'), /^\/login\?next=/);
  }
});

test('wrong passwords are refused, and 5 wrong tries lock the account for a while', async () => {
  const { login } = await setup();
  assert.equal((await login('owner', 'nope')).r.status, 401);
  for (let i = 0; i < 4; i++) await login('owner', 'nope');
  const locked = await login('owner', OWNER_PW);
  assert.equal(locked.r.status, 401);
  assert.match(await locked.r.text(), /Too many tries/);
});

test('the owner sees every phone and the counts', async () => {
  const { login, call, phone } = await setup();
  const a = await phone('install-aaaaaaaaaaaaaaaa');
  await phone('install-bbbbbbbbbbbbbbbb');
  const { r, cookie } = await login('owner', OWNER_PW);
  assert.equal(r.status, 303);
  assert.equal(r.headers.get('location'), '/admin');
  assert.match(r.headers.get('set-cookie'), /HttpOnly; SameSite=Strict/);
  const page = await (await call('/admin', { headers: { cookie } })).text();
  assert.match(page, /Phones<\/span><b>2</);
  assert.ok(page.includes(a.payCode));
});

test('owner adds a staff member; staff can find a phone by code but not see the list', async () => {
  const { login, call, form, phone } = await setup();
  const a = await phone();
  const owner = await login('owner', OWNER_PW);
  const add = await form('/admin/staff', { username: 'dana', password: 'staff-password-1' }, owner.cookie);
  assert.match(add.headers.get('location'), /ok=Added/);
  const short = await form('/admin/staff', { username: 'eli', password: 'short' }, owner.cookie);
  assert.match(short.headers.get('location'), /err=/);

  const staff = await login('dana', 'staff-password-1');
  assert.equal(staff.r.headers.get('location'), '/support');
  assert.equal((await call('/admin', { headers: { cookie: staff.cookie } })).status, 403);
  assert.equal((await call('/admin/staff', { headers: { cookie: staff.cookie } })).status, 403);
  const found = await call(`/support/find?code=${a.payCode.toLowerCase()}`, { headers: { cookie: staff.cookie } });
  assert.equal(found.headers.get('location'), `/support/phone/${a.payCode}`);
  const card = await (await call(found.headers.get('location'), { headers: { cookie: staff.cookie } })).text();
  assert.match(card, /Unlock \(mark paid\)/);
  assert.match(card, /Remove the filter/);
  assert.doesNotMatch(card, /Lock again/);
  const missing = await call('/support/find?code=AOS-ZZZZ-ZZZZ', { headers: { cookie: staff.cookie } });
  assert.match(missing.headers.get('location'), /err=No%20phone/);
});

test('staff unlock marks the phone paid, with the reason in history and the action log', async () => {
  const { login, call, form, phone, license, store } = await setup();
  const a = await phone();
  const owner = await login('owner', OWNER_PW);
  await form('/admin/staff', { username: 'dana', password: 'staff-password-1' }, owner.cookie);
  const staff = await login('dana', 'staff-password-1');
  const noReason = await form(`/portal/device/${a.deviceId}/unlock`, { reason: '', back: '/support' }, staff.cookie);
  assert.match(noReason.headers.get('location'), /err=/);
  const r = await form(`/portal/device/${a.deviceId}/unlock`, { reason: 'Paid by phone call', back: `/support/phone/${a.payCode}` }, staff.cookie);
  assert.match(r.headers.get('location'), new RegExp(`^/support/phone/${a.payCode}\\?ok=Unlocked`));
  const l = await license(a.token);
  assert.equal(l.status, 'paid');
  assert.equal((await verifyLicense(l.license, keys.publicSpkiB64)).status, 'paid');
  const d = await store.getDevice(a.deviceId);
  assert.deepEqual([d.history.at(-1).by, d.history.at(-1).reason], ['dana', 'Paid by phone call']);
  const log = await (await call('/admin/audit', { headers: { cookie: owner.cookie } })).text();
  assert.match(log, /dana.*unlock.*Paid by phone call/s);
});

test('removing the filter needs the phone code typed exactly; the phone then gets a signed "removed" license', async () => {
  const { login, call, form, phone, license, store } = await setup();
  const a = await phone();
  const owner = await login('owner', OWNER_PW);
  const wrong = await form(`/portal/device/${a.deviceId}/remove`, { reason: 'Customer asked', confirm: 'AOS-0000-0000', back: '/admin' }, owner.cookie);
  assert.match(wrong.headers.get('location'), /err=The%20code/);
  assert.equal((await store.getDevice(a.deviceId)).status, 'unpaid');
  await form(`/portal/device/${a.deviceId}/remove`, { reason: 'Customer asked', confirm: a.payCode.toLowerCase(), back: '/admin' }, owner.cookie);
  assert.equal((await store.getDevice(a.deviceId)).status, 'removed');
  let page = await (await call(`/admin/device/${a.deviceId}`, { headers: { cookie: owner.cookie } })).text();
  assert.match(page, /Waiting for the phone to check in/);
  const l = await license(a.token);
  assert.equal((await verifyLicense(l.license, keys.publicSpkiB64)).status, 'removed');
  page = await (await call(`/admin/device/${a.deviceId}`, { headers: { cookie: owner.cookie } })).text();
  assert.match(page, /The phone confirmed the removal/);
  // Nothing more can be done to a removed phone.
  const again = await form(`/portal/device/${a.deviceId}/unlock`, { reason: 'x', back: '/admin' }, owner.cookie);
  assert.match(again.headers.get('location'), /err=This%20phone%20is%20already%20removed/);
});

test('staff cannot lock a phone again; only the owner can', async () => {
  const { login, form, phone, store } = await setup();
  const a = await phone();
  const owner = await login('owner', OWNER_PW);
  await form('/admin/staff', { username: 'dana', password: 'staff-password-1' }, owner.cookie);
  const staff = await login('dana', 'staff-password-1');
  await form(`/portal/device/${a.deviceId}/unlock`, { reason: 'paid', back: '/support' }, staff.cookie);
  const r = await form(`/portal/device/${a.deviceId}/lock`, { reason: 'refund', back: '/support' }, staff.cookie);
  assert.match(r.headers.get('location'), /err=That%20action%20is%20not%20allowed/);
  assert.equal((await store.getDevice(a.deviceId)).status, 'paid');
  await form(`/portal/device/${a.deviceId}/lock`, { reason: 'refund', back: '/admin' }, owner.cookie);
  assert.equal((await store.getDevice(a.deviceId)).status, 'unpaid');
});

test('turning a staff member off ends their access right away', async () => {
  const { login, call, form } = await setup();
  const owner = await login('owner', OWNER_PW);
  await form('/admin/staff', { username: 'dana', password: 'staff-password-1' }, owner.cookie);
  const staff = await login('dana', 'staff-password-1');
  assert.equal((await call('/support', { headers: { cookie: staff.cookie } })).status, 200);
  await form('/admin/staff/dana/disable', {}, owner.cookie);
  assert.equal((await call('/support', { headers: { cookie: staff.cookie } })).status, 303);
  assert.equal((await login('dana', 'staff-password-1')).r.status, 401);
});

test('form posts from another site are refused', async () => {
  const { login, form, phone, store } = await setup();
  const a = await phone();
  const owner = await login('owner', OWNER_PW);
  const r = await form(`/portal/device/${a.deviceId}/unlock`, { reason: 'x', back: '/admin' }, owner.cookie, 'https://evil.example');
  assert.equal(r.status, 403);
  assert.equal((await store.getDevice(a.deviceId)).status, 'unpaid');
});

test('signing out ends the session', async () => {
  const { login, call, form } = await setup();
  const owner = await login('owner', OWNER_PW);
  await form('/logout', {}, owner.cookie);
  assert.equal((await call('/admin', { headers: { cookie: owner.cookie } })).status, 303);
});

test('a redirect after an action never leaves the site', async () => {
  const { login, form, phone } = await setup();
  const a = await phone();
  const owner = await login('owner', OWNER_PW);
  const r = await form(`/portal/device/${a.deviceId}/unlock`, { reason: 'x', back: '//evil.example/' }, owner.cookie);
  assert.match(r.headers.get('location'), /^\/support\?/);
});

test('pages keep the Origin header on our own form posts (no "no-referrer" policy)', async () => {
  const { call } = await setup();
  const r = await call('/login');
  assert.equal(r.headers.get('referrer-policy'), 'same-origin');
});
