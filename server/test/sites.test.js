import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createApp } from '../src/app.js';
import { createSigner } from '../src/signer.js';
import { MemoryStore } from '../src/store.js';
import { TestPayments } from '../src/payments.js';
import { OutboxMailer } from '../src/services.js';
import { cleanSite } from '../src/sites.js';

const keys = JSON.parse(readFileSync(new URL('../test-keys.json', import.meta.url)));
const BASE = 'http://server.test';

async function setup() {
  const store = new MemoryStore();
  const mailer = new OutboxMailer();
  const app = createApp({ store, signer: await createSigner(keys.privateJwk), payments: new TestPayments(), adminToken: 'admin-secret', baseUrl: BASE, ownerPassword: 'owner-password-1', mailer });
  const call = (path, init = {}) => app.handle(new Request(BASE + path, { redirect: 'manual', ...init }));
  const json = (path, body, token) => call(path, { method: 'POST', body: JSON.stringify(body), headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) } });
  const form = (path, body, cookie = '') => call(path, { method: 'POST', body: new URLSearchParams(body).toString(), headers: { 'content-type': 'application/x-www-form-urlencoded', cookie, origin: BASE } });
  const signIn = async (username, password) => {
    const r = await form('/login', { username, password });
    return (r.headers.get('set-cookie') ?? '').split(';')[0];
  };
  const get = (path, cookie, token) => call(path, { headers: { ...(cookie ? { cookie } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}) } });
  const phone = async (n) => (await json('/v1/activate', { installId: `install-0123456789abcd-${n}` })).json();
  return { app, store, mailer, call, json, form, signIn, get, phone };
}

test('website names are checked: app ids and host names only', () => {
  assert.deepEqual(cleanSite({ app: 'com.whatsapp', host: 'Media.WhatsApp.NET.' }), { app: 'com.whatsapp', host: 'media.whatsapp.net' });
  assert.equal(cleanSite({ app: 'whatsapp', host: 'a.whatsapp.net' }), null);
  assert.equal(cleanSite({ app: 'com.x', host: '1.2.3.4' }), null);
  assert.equal(cleanSite({ app: 'com.x', host: 'evil.com/<script>' }), null);
  assert.equal(cleanSite({ app: 'com.x', host: 'localhost' }), null);
});

test('phones report blocked websites; the owner approves one and every phone gets it for that app only', async () => {
  const { json, form, signIn, get, phone, mailer, app } = await setup();
  const a = await phone(1);
  const b = await phone(2);
  assert.equal((await json('/v1/blocked-sites', { sites: [{ app: 'com.example.chat', host: 'media.examplecdn.net' }] }, 'wrong-token')).status, 401);
  let r = await (await json('/v1/blocked-sites', { sites: [{ app: 'com.example.chat', host: 'media.examplecdn.net' }, { app: 'com.example.chat', host: 'www.facebook.com' }, { app: 'bad', host: 'x' }] }, a.token)).json();
  assert.deepEqual(r.apps, {});
  await json('/v1/blocked-sites', { sites: [{ app: 'com.example.chat', host: 'media.examplecdn.net' }] }, b.token);
  await json('/v1/blocked-sites', { sites: [{ app: 'com.example.chat', host: 'media.examplecdn.net' }] }, b.token); // same phone again: not counted twice

  const cookie = await signIn('owner', 'owner-password-1');
  const home = await (await get('/owner', cookie)).text();
  assert.match(home, /Apps need 2 websites you haven&#39;t approved|Apps need 2 websites you haven't approved/);
  const page = await (await get('/owner/app-sites', cookie)).text();
  assert.match(page, /media\.examplecdn\.net/);
  assert.match(page, /<td>2<\/td>/); // reported by 2 phones
  assert.doesNotMatch(page, />x</);

  const open = await app.sites.list('open');
  const media = open.find((x) => x.host === 'media.examplecdn.net');
  const fb = open.find((x) => x.host === 'www.facebook.com');
  assert.equal((await form(`/owner/app-sites/${media.id}/approve`, {}, cookie)).status, 303);
  await form(`/owner/app-sites/${fb.id}/dismiss`, {}, cookie);

  const sites = await (await get('/v1/app-sites', '', b.token)).json();
  assert.deepEqual(sites.apps, { 'com.example.chat': ['media.examplecdn.net'] });
  assert.equal((await get('/v1/app-sites', '', 'nope')).status, 401);
  assert.match(await (await get('/owner/actions', cookie)).text(), /Approved an app website/);

  // Take it back: blocked again and waiting.
  await form(`/owner/app-sites/${media.id}/undo`, {}, cookie);
  assert.deepEqual((await (await get('/v1/app-sites', '', a.token)).json()).apps, {});

  // The daily email mentions what waits for the owner.
  await app.services.dailySummary('owner@example.com');
  assert.match(mailer.sent.at(-1).text, /1 app website waits for your approval/);
});

test('staff can not approve app websites', async () => {
  const { json, form, signIn, phone, app } = await setup();
  const a = await phone(1);
  await json('/v1/blocked-sites', { sites: [{ app: 'com.example.chat', host: 'media.examplecdn.net' }] }, a.token);
  const owner = await signIn('owner', 'owner-password-1');
  await form('/owner/staff', { username: 'dana', password: 'staff-password-1' }, owner);
  const staff = await signIn('dana', 'staff-password-1');
  const [r] = await app.sites.list('open');
  const res = await form(`/owner/app-sites/${r.id}/approve`, {}, staff);
  assert.equal(res.headers.get('location'), '/staff');
  assert.equal((await app.sites.list('open')).length, 1);
});
