import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createApp } from '../src/app.js';
import { createSigner, verifyLicense } from '../src/signer.js';
import { MemoryStore } from '../src/store.js';
import { TestPayments } from '../src/payments.js';
import { OutboxMailer } from '../src/services.js';

const keys = JSON.parse(readFileSync(new URL('../test-keys.json', import.meta.url)));
const BASE = 'http://server.test';
const owner = { name: 'owner', role: 'owner' };
const staff = { name: 'dana', role: 'staff' };

async function setup({ clock } = {}) {
  const store = new MemoryStore();
  const mailer = new OutboxMailer();
  const app = createApp({ store, signer: await createSigner(keys.privateJwk), payments: new TestPayments(), adminToken: 'admin-secret', baseUrl: BASE, mailer, clock });
  const call = (path, init = {}) => app.handle(new Request(BASE + path, { redirect: 'manual', ...init }));
  const post = (path, body, token) => call(path, {
    method: 'POST', body: typeof body === 'string' ? body : JSON.stringify(body),
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
  });
  const phone = async (n = 1, details = {}) => {
    const a = await (await post('/v1/activate', { installId: `install-0123456789abcd-${n}` })).json();
    await post('/v1/customer', { customer: { name: `Customer ${n}`, phone: `+1 212 555 010${n}`, email: `c${n}@example.com`, removalCode: `code-${n}-secret`, ...details } }, a.token);
    return a;
  };
  return { store, mailer, app, s: app.services, call, post, phone };
}

test('search by name, phone digits or email; short searches return nothing', async () => {
  const { s, phone } = await setup();
  await phone(1, { name: 'Dana Levi' });
  await phone(2, { name: 'Eli Cohen', phone: '(718) 555-9999' });
  assert.equal((await s.searchCustomers('levi'))[0].name, 'Dana Levi');
  assert.equal((await s.searchCustomers('718-555-99'))[0].name, 'Eli Cohen');
  assert.equal((await s.searchCustomers('C2@EXAMPLE'))[0].name, 'Eli Cohen');
  assert.deepEqual(await s.searchCustomers('el'), []);
  const view = (await s.searchCustomers('levi'))[0];
  assert.ok(!('removalCodeHash' in view) && !('tokenHash' in view));
});

test("staff remove a filter with the customer's code; the phone then gets a signed 'removed' license", async () => {
  const { s, phone, store, call } = await setup();
  const a = await phone(1);
  await assert.rejects(s.removeWithCode(staff, a.deviceId, 'code-1-secret', ''), /reason/);
  await s.removeWithCode(staff, a.deviceId, 'code-1-secret', 'Customer asked by phone');
  const d = await store.getDevice(a.deviceId);
  assert.equal(d.status, 'removed');
  assert.deepEqual([d.history.at(-1).by, d.history.at(-1).reason], ['dana', 'Customer asked by phone']);
  const l = await (await call('/v1/license', { headers: { authorization: `Bearer ${a.token}` } })).json();
  assert.equal((await verifyLicense(l.license, keys.publicSpkiB64)).status, 'removed');
  assert.ok((await store.getDevice(a.deviceId)).removedSeenAt, 'the portal can show the phone got the order');
  const log = await store.listAudit();
  assert.equal(log.at(-1).action, 'remove');
});

test('wrong codes change nothing; 5 wrong codes block removal for an hour and alert the owner', async () => {
  let t = Date.parse('2026-12-01T10:00:00Z');
  const { s, phone, store } = await setup({ clock: () => new Date(t) });
  const a = await phone(1);
  for (let i = 1; i <= 4; i++) await assert.rejects(s.removeWithCode(staff, a.deviceId, 'nope', 'r'), /wrong/);
  await assert.rejects(s.removeWithCode(staff, a.deviceId, 'nope', 'r'), /blocked for an hour/);
  await assert.rejects(s.removeWithCode(staff, a.deviceId, 'code-1-secret', 'r'), /blocked/);
  assert.equal((await store.getDevice(a.deviceId)).status, 'unpaid');
  const alerts = await s.listAlerts();
  assert.equal(alerts[0].type, 'removal_locked');
  t += 61 * 60 * 1000;
  await s.removeWithCode(staff, a.deviceId, 'code-1-secret', 'r');
  assert.equal((await store.getDevice(a.deviceId)).status, 'removed');
});

test('only the owner can remove without a code, mark paid or lock again', async () => {
  const { s, phone, store } = await setup();
  const a = await phone(1);
  await assert.rejects(s.removeOverride(staff, a.deviceId, 'x'), /Only the owner/);
  await assert.rejects(s.setPaid(staff, a.deviceId, true, 'x'), /Only the owner/);
  await s.setPaid(owner, a.deviceId, true, 'Paid in cash');
  assert.equal((await store.getDevice(a.deviceId)).status, 'paid');
  await s.setPaid(owner, a.deviceId, false, 'Refund');
  assert.equal((await store.getDevice(a.deviceId)).status, 'unpaid');
  await s.removeOverride(owner, a.deviceId, 'Lost email access, ID checked');
  assert.equal((await store.getDevice(a.deviceId)).status, 'removed');
  assert.equal((await store.listAudit()).at(-1).action, 'remove-without-code');
});

test('code reset: email with a one-time link; the new code works and the old one does not', async () => {
  const { s, phone, mailer, call } = await setup();
  const a = await phone(1);
  await s.sendCodeReset(staff, a.deviceId);
  assert.equal(mailer.sent.length, 1);
  assert.equal(mailer.sent[0].to, 'c1@example.com');
  const link = mailer.sent[0].text.match(/http:\/\/server\.test(\/reset\/\S+)/)[1];
  assert.match(await (await call(link)).text(), /New removal code/);
  const form = (body) => call(link, { method: 'POST', body: new URLSearchParams(body).toString(), headers: { 'content-type': 'application/x-www-form-urlencoded' } });
  assert.equal((await form({ code: 'short', code2: 'short' })).status, 400);
  assert.equal((await form({ code: 'brand-new-code', code2: 'other-code' })).status, 400);
  assert.match(await (await form({ code: 'brand-new-code', code2: 'brand-new-code' })).text(), /Saved/);
  assert.equal((await call(link)).status, 404, 'the link works once');
  await assert.rejects(s.removeWithCode(staff, a.deviceId, 'code-1-secret', 'r'), /wrong/);
  await s.removeWithCode(staff, a.deviceId, 'brand-new-code', 'r');
  assert.ok((await s.listAlerts({ open: false })).some((x) => x.type === 'code_reset'));
});

test('reset links expire after 24 hours', async () => {
  let t = Date.parse('2026-12-01T10:00:00Z');
  const { s, phone, mailer, call } = await setup({ clock: () => new Date(t) });
  const a = await phone(1);
  await s.sendCodeReset(staff, a.deviceId);
  const link = mailer.sent[0].text.match(/http:\/\/server\.test(\/reset\/\S+)/)[1];
  t += 25 * 3600 * 1000;
  assert.equal((await call(link)).status, 404);
});

test('alerts: failed setups, tamper reports, unpaid after 3 days; one open alert per phone and type', async () => {
  let t = Date.parse('2026-12-01T10:00:00Z');
  const { s, phone, post, store } = await setup({ clock: () => new Date(t) });
  const a = await phone(1);
  await post('/v1/installer-logs', 'STEP Checking\nERROR Could not install the app: no space\nRESULT failed');
  await post('/v1/tamper', { events: [{ type: 'kiosk_mode_exited' }, { type: 'web_filter_revoked' }] }, a.token);
  await post('/v1/tamper', { events: [{ type: 'kiosk_mode_exited' }] }, a.token);
  assert.equal((await post('/v1/tamper', { events: [] }, a.token)).status, 400);
  assert.equal((await post('/v1/tamper', { events: [{ type: 'x' }] })).status, 401);
  // The phone's createdAt comes from the real clock; age it.
  const d = await store.getDevice(a.deviceId);
  d.createdAt = new Date(t - 4 * 86400 * 1000).toISOString();
  await store.putDevice(d);
  assert.equal(await s.checkUnpaid(), 1);
  const types = (await s.listAlerts()).map((x) => x.type).sort();
  assert.deepEqual(types, ['setup_failed', 'tamper', 'unpaid']);
  assert.match((await s.listAlerts()).find((x) => x.type === 'setup_failed').detail, /no space/);
  const first = (await s.listAlerts())[0];
  await assert.rejects(s.handleAlert(staff, first.id), /Only the owner/);
  await s.handleAlert(owner, first.id);
  assert.equal((await s.listAlerts()).length, 2);
});

test('daily summary: one email a day, with open alerts and the last 24 hours', async () => {
  let t = new Date(2026, 11, 1, 7, 0).getTime();
  const { s, phone, mailer, post } = await setup({ clock: () => new Date(t) });
  const a = await phone(1);
  await post('/v1/tamper', { events: [{ type: 'web_filter_revoked' }] }, a.token);
  await s.removeWithCode(staff, a.deviceId, 'code-1-secret', 'Customer called');
  mailer.sent.length = 0;
  assert.equal(await s.maybeDailySummary('owner@example.com', 8), false, 'not before 8');
  t += 2 * 3600 * 1000;
  assert.equal(await s.maybeDailySummary('owner@example.com', 8), true);
  assert.equal(await s.maybeDailySummary('owner@example.com', 8), false, 'once a day');
  assert.equal(await s.maybeDailySummary('', 8), false, 'no address, no email');
  assert.equal(mailer.sent.length, 1);
  const m = mailer.sent[0];
  assert.equal(m.to, 'owner@example.com');
  assert.match(m.subject, /1 alert needs you/);
  assert.match(m.text, /Tamper attempt: Customer 1/);
  assert.match(m.text, /Filters removed: 1 \(dana: Customer called\)/);
  assert.match(m.text, /http:\/\/server\.test\/owner/);
  assert.doesNotMatch(m.text, /code-1-secret/);
  t += 24 * 3600 * 1000;
  assert.equal(await s.maybeDailySummary('owner@example.com', 8), true, 'next day again');
});
