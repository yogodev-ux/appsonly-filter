// Shared bits for the web pages: the one sign-in page, safe page headers, and small helpers.
// The owner portal (owner.js) and the staff portal (staff.js) each have their own look and pages;
// only the sign-in page is shared. After signing in, the role decides which portal opens.
import { escapeHtml as e } from './util.js';

export const SECURITY_HEADERS = {
  'content-type': 'text/html; charset=utf-8',
  'cache-control': 'no-store',
  'x-frame-options': 'DENY',
  'x-content-type-options': 'nosniff',
  'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
  // Not no-referrer: then browsers send "Origin: null" on form posts and the same-site check fails.
  'referrer-policy': 'same-origin',
};

export function htmlPage({ title, css, body, status = 200, headers = {} }) {
  return new Response(
    `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
      `<meta name="robots" content="noindex"><title>${e(title)}</title><style>${css}</style></head><body>${body}</body></html>`,
    { status, headers: { ...SECURITY_HEADERS, ...headers } },
  );
}

export const redirect = (to, headers = {}) => new Response(null, { status: 303, headers: { location: to, ...headers } });

export async function formData(request) {
  return Object.fromEntries(new URLSearchParams(await request.text()));
}

/** "2026-12-01 10:00 UTC", or "never". */
export const when = (iso) => (iso ? e(String(iso).replace('T', ' ').slice(0, 16)) + ' UTC' : '<span class="muted">never</span>');

/** Success / error banner from ?ok= and ?err= after a redirect. */
export function banner(url) {
  const ok = url.searchParams.get('ok');
  const bad = url.searchParams.get('err');
  return (ok ? `<div class="msg ok" role="status">${e(ok)}</div>` : '') + (bad ? `<div class="msg bad" role="alert">${e(bad)}</div>` : '');
}

export const withMsg = (path, key, text) => `${path}${path.includes('?') ? '&' : '?'}${key}=${encodeURIComponent(text)}`;

export const portalHome = (role) => (role === 'owner' ? '/owner' : '/staff');

const LOGIN_CSS = `
:root{--bg:#eef1f5;--card:#fff;--text:#1d2330;--muted:#5d6675;--line:#d6dbe3;--accent:#2a4a7f;--danger:#b3261e}
@media (prefers-color-scheme:dark){:root{--bg:#121519;--card:#1c2027;--text:#e6e9ef;--muted:#9aa3b2;--line:#2e343e;--accent:#7fa6e6;--danger:#f2706a}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}
.box{max-width:22rem;margin:12vh auto 2rem;padding:1.6rem;background:var(--card);border:1px solid var(--line);border-radius:12px}
h1{font-size:1.3rem;margin:0 0 1.2rem}label{display:block;margin:.8rem 0 .25rem;color:var(--muted);font-size:.9rem}
input{width:100%;font:inherit;padding:.55rem .7rem;border:1px solid var(--line);border-radius:8px;background:var(--bg);color:var(--text)}
button{width:100%;margin-top:1.2rem;font:inherit;font-weight:600;padding:.6rem;border-radius:8px;border:0;background:var(--accent);color:#fff;cursor:pointer}
.bad{color:var(--danger);margin:0 0 .6rem}
@media (max-width:30rem){.box{margin:2rem 16px}}
`;

export function loginPage(error = '', status = 200) {
  return htmlPage({
    title: 'Sign in · Apps Only',
    css: LOGIN_CSS,
    status,
    body: `<div class="box"><h1>Apps Only sign-in</h1>${error ? `<p class="bad" role="alert">${e(error)}</p>` : ''}
<form method="post" action="/login">
<label for="u">User name</label><input id="u" name="username" autocomplete="username" required maxlength="64">
<label for="p">Password</label><input id="p" name="password" type="password" autocomplete="current-password" required maxlength="200">
<button>Sign in</button></form></div>`,
  });
}

/**
 * /login, /logout, and the guard for /owner and /staff.
 * Returns a Response for these paths, or null when the path isn't a web page.
 */
export function createWeb({ auth, owner, staff }) {
  async function handle(request, url) {
    const p = url.pathname;
    const method = request.method;
    const isPortal = p === '/owner' || p.startsWith('/owner/') || p === '/staff' || p.startsWith('/staff/');
    if (p !== '/login' && p !== '/logout' && p !== '/' && !isPortal) return null;
    if (method === 'POST' && !auth.sameOrigin(request)) return new Response('Forbidden', { status: 403 });

    const who = await auth.current(request);
    if (p === '/' && method === 'GET') return redirect(who ? portalHome(who.role) : '/login');
    if (p === '/login') {
      if (method === 'GET') return who ? redirect(portalHome(who.role)) : loginPage();
      if (method === 'POST') {
        const f = await formData(request);
        const r = await auth.login(f.username, f.password);
        if (r.error === 'locked') return loginPage('Too many wrong tries. Wait 15 minutes and try again.', 429);
        if (r.error) return loginPage('Wrong user name or password.', 401);
        return redirect(portalHome(r.user.role), { 'set-cookie': r.cookie });
      }
    }
    if (p === '/logout' && method === 'POST') return redirect('/login', { 'set-cookie': await auth.logout(request) });
    if (!isPortal) return null;

    if (!who) return redirect('/login');
    // Each person only ever sees their own portal.
    const wantsOwner = p === '/owner' || p.startsWith('/owner/');
    if (wantsOwner && who.role !== 'owner') return redirect('/staff');
    if (!wantsOwner && who.role !== 'staff') return redirect('/owner');
    return wantsOwner ? owner.handle(request, url, who) : staff.handle(request, url, who);
  }
  return { handle };
}
