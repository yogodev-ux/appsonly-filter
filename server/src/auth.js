// One sign-in page for the owner portal and the staff portal; the role decides where you land.
// Passwords: PBKDF2-SHA256 (Web Crypto, so it runs anywhere). Sessions: a random id in an
// HttpOnly, SameSite=Strict cookie; only its SHA-256 is stored.
import { randomToken, sha256Hex, timingSafeEqual } from './util.js';
import { checkSecret, hashSecret } from './secrets.js';

const SESSION_HOURS = 12;
export const COOKIE = 'aos_session';
export const ROLES = new Set(['owner', 'staff']);
export { hashSecret as hashPassword, checkSecret as checkPassword };

/** Rules for staff passwords set in the dashboard. Returns an error text, or '' if fine. */
export function passwordProblem(pw) {
  if (typeof pw !== 'string' || pw.length < 10) return 'Passwords need at least 10 characters.';
  if (pw.length > 200) return 'That password is too long.';
  return '';
}

export function validUsername(name) {
  return typeof name === 'string' && /^[a-z0-9._-]{3,32}$/.test(name);
}

export function cookieValue(request, name) {
  for (const part of (request.headers.get('cookie') ?? '').split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k === name) return v.join('=');
  }
  return '';
}

export function createAuth({ store, baseUrl, ownerPassword = '' }) {
  const secure = baseUrl.startsWith('https://');
  const failures = new Map(); // username -> {count, until}

  function lockedOut(name) {
    const f = failures.get(name);
    return Boolean(f && f.until > Date.now());
  }

  function noteFailure(name) {
    const f = failures.get(name) ?? { count: 0, until: 0 };
    f.count += 1;
    if (f.count >= 5) {
      f.until = Date.now() + 15 * 60 * 1000;
      f.count = 0;
    }
    failures.set(name, f);
  }

  /** Returns {name, role} or null. The owner account comes from OWNER_PASSWORD. */
  async function login(name, password) {
    name = String(name ?? '').trim().toLowerCase();
    if (!name || lockedOut(name)) return { error: lockedOut(name) ? 'locked' : 'bad' };
    let user = null;
    if (name === 'owner') {
      if (ownerPassword && timingSafeEqual(String(password), ownerPassword)) user = { name: 'owner', role: 'owner' };
    } else {
      const u = await store.getUser(name);
      if (u && !u.disabled && (await checkSecret(String(password), u.passwordHash))) user = { name: u.name, role: u.role };
    }
    if (!user) {
      noteFailure(name);
      return { error: 'bad' };
    }
    failures.delete(name);
    const id = randomToken();
    await store.putSession({
      idHash: await sha256Hex(id),
      name: user.name,
      role: user.role,
      created: Date.now(),
      expires: Date.now() + SESSION_HOURS * 3600 * 1000,
    });
    return { user, cookie: `${COOKIE}=${id}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${SESSION_HOURS * 3600}${secure ? '; Secure' : ''}` };
  }

  /** The signed-in person for this request, or null. Disabled staff lose access at once. */
  async function current(request) {
    const id = cookieValue(request, COOKIE);
    if (!id) return null;
    const s = await store.getSession(await sha256Hex(id));
    if (!s || s.expires < Date.now()) return null;
    if (s.role !== 'owner') {
      const u = await store.getUser(s.name);
      if (!u || u.disabled) return null;
      return { name: u.name, role: u.role };
    }
    return { name: s.name, role: s.role };
  }

  async function logout(request) {
    const id = cookieValue(request, COOKIE);
    if (id) await store.deleteSession(await sha256Hex(id));
    return `${COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0${secure ? '; Secure' : ''}`;
  }

  /** Form posts must come from our own pages (on top of the SameSite cookie). */
  function sameOrigin(request) {
    const origin = request.headers.get('origin');
    if (!origin) return true; // some browsers leave it out on same-origin posts
    const own = new URL(request.url).origin;
    return origin === own || origin === new URL(baseUrl).origin;
  }

  return { login, current, logout, sameOrigin };
}
