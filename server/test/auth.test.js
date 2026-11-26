import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createAuth, hashPassword } from '../src/auth.js';
import { MemoryStore } from '../src/store.js';

const BASE = 'http://server.test';
const req = (cookie = '', origin) => new Request(BASE + '/x', { headers: { cookie, ...(origin ? { origin } : {}) } });

async function setup() {
  const store = new MemoryStore();
  await store.putUser({ name: 'dana', role: 'staff', passwordHash: await hashPassword('staff-password-1'), disabled: false });
  return { store, auth: createAuth({ store, baseUrl: BASE, ownerPassword: 'owner-password-1' }) };
}

test('one sign-in: the role says which portal to open', async () => {
  const { auth } = await setup();
  const o = await auth.login('owner', 'owner-password-1');
  const s = await auth.login('DANA', 'staff-password-1');
  assert.equal(o.user.role, 'owner');
  assert.equal(s.user.role, 'staff');
  assert.match(s.cookie, /HttpOnly; SameSite=Strict/);
  assert.deepEqual(await auth.current(req(s.cookie.split(';')[0])), { name: 'dana', role: 'staff' });
});

test('wrong passwords fail; 5 wrong tries lock the user name', async () => {
  const { auth } = await setup();
  assert.equal((await auth.login('dana', 'nope')).error, 'bad');
  for (let i = 0; i < 4; i++) await auth.login('dana', 'nope');
  assert.equal((await auth.login('dana', 'staff-password-1')).error, 'locked');
  assert.equal((await auth.login('owner', '')).error, 'bad');
});

test('turning staff off ends their session at once; sign-out ends it too', async () => {
  const { auth, store } = await setup();
  const s = await auth.login('dana', 'staff-password-1');
  const cookie = s.cookie.split(';')[0];
  const u = await store.getUser('dana');
  u.disabled = true;
  await store.putUser(u);
  assert.equal(await auth.current(req(cookie)), null);
  const o = await auth.login('owner', 'owner-password-1');
  const oc = o.cookie.split(';')[0];
  await auth.logout(req(oc));
  assert.equal(await auth.current(req(oc)), null);
});

test('posts from other sites are refused', async () => {
  const { auth } = await setup();
  assert.equal(auth.sameOrigin(req('', 'https://evil.example')), false);
  assert.equal(auth.sameOrigin(req('', BASE)), true);
});
