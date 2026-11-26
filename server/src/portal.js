// Step 5: the owner's private dashboard (/admin) and the staff support portal (/support).
//
// Owner (user "owner", password from OWNER_PASSWORD): sees every phone, payments, setup logs,
// the action log, and adds or turns off staff accounts.
// Staff (accounts the owner creates): find ONE phone by the code shown on it, then unlock it
// (mark paid) or remove the filter. They can't list phones or see other staff.
// Every action is written to the action log with who did it and why.
import { escapeHtml as e } from './util.js';
import { hashPassword, passwordProblem, validUsername } from './auth.js';

const CSS = `
:root{--bg:#f6f7f9;--card:#fff;--text:#1d2330;--muted:#5d6675;--line:#dfe3ea;--accent:#0b63a8;--danger:#b3261e;--ok:#1b7f3b;--warn:#9a6700}
@media (prefers-color-scheme:dark){:root{--bg:#14171c;--card:#1d2128;--text:#e6e9ef;--muted:#9aa3b2;--line:#2e343e;--accent:#5aa9e6;--danger:#f2706a;--ok:#5cc982;--warn:#e0b44c}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,Segoe UI,sans-serif}
header{background:var(--card);border-bottom:1px solid var(--line);padding:.7rem 1rem;display:flex;gap:1rem;align-items:center;flex-wrap:wrap}
header b{margin-right:auto}header a{color:var(--accent);text-decoration:none}
main{max-width:64rem;margin:0 auto;padding:1rem}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:1rem;margin:0 0 1rem}
h1{font-size:1.4rem;margin:.2rem 0 1rem}h2{font-size:1.1rem;margin:0 0 .6rem}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(9rem,1fr));gap:.7rem;margin-bottom:1rem}
.stat{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:.7rem 1rem}
.stat span{display:block;color:var(--muted);font-size:.85rem}.stat b{font-size:1.6rem}
table{width:100%;border-collapse:collapse;font-size:.92rem}th,td{text-align:left;padding:.45rem .5rem;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--muted);font-weight:600}.scroll{overflow-x:auto}
code{font-size:.95em}.muted{color:var(--muted)}
.pill{display:inline-block;border-radius:99px;padding:0 .55rem;font-size:.82rem;font-weight:600;border:1px solid currentColor}
.paid{color:var(--ok)}.unpaid{color:var(--warn)}.removed{color:var(--muted)}
input,select,textarea{font:inherit;padding:.45rem .6rem;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--text);max-width:100%}
button{font:inherit;padding:.45rem 1rem;border-radius:6px;border:1px solid var(--accent);background:var(--accent);color:#fff;cursor:pointer}
button.danger{background:var(--danger);border-color:var(--danger)}button.plain{background:transparent;color:var(--accent)}
form.row{display:flex;gap:.5rem;flex-wrap:wrap;align-items:center}
.msg{padding:.6rem 1rem;border-radius:8px;margin-bottom:1rem;border:1px solid var(--line);background:var(--card)}
.msg.bad{border-color:var(--danger);color:var(--danger)}
label{display:block;margin:.4rem 0 .2rem;color:var(--muted);font-size:.9rem}
pre{white-space:pre-wrap;word-break:break-word;font-size:.85rem}
`;

function page(title, body, who, status = 200, headers = {}) {
  const nav = !who ? '' : who.role === 'owner'
    ? `<a href="/admin">Phones</a><a href="/admin/logs">Setup logs</a><a href="/admin/staff">Staff</a><a href="/admin/audit">Action log</a><a href="/support">Support</a>`
    : `<a href="/support">Find a phone</a>`;
  const out = who ? `<form method="post" action="/logout" style="margin:0"><button class="plain">Sign out (${e(who.name)})</button></form>` : '';
  return new Response(
    `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
      `<meta name="robots" content="noindex"><title>${e(title)} · Apps Only</title><style>${CSS}</style></head><body>` +
      `<header><b>Apps Only</b>${nav}${out}</header><main>${body}</main></body></html>`,
    {
      status,
      headers: {
        'content-type': 'text/html; charset=utf-8',
        'cache-control': 'no-store',
        'x-frame-options': 'DENY',
        'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'",
        'referrer-policy': 'no-referrer',
        ...headers,
      },
    },
  );
}

const redirect = (to, headers = {}) => new Response(null, { status: 303, headers: { location: to, ...headers } });
const pill = (s) => `<span class="pill ${e(s)}">${e(s)}</span>`;
const when = (iso) => (iso ? e(String(iso).replace('T', ' ').slice(0, 16)) + ' UTC' : '<span class="muted">never</span>');
const msg = (url) => {
  const ok = url.searchParams.get('ok');
  const bad = url.searchParams.get('err');
  return (ok ? `<div class="msg">${e(ok)}</div>` : '') + (bad ? `<div class="msg bad">${e(bad)}</div>` : '');
};

async function formData(request) {
  const text = await request.text();
  return Object.fromEntries(new URLSearchParams(text));
}

/** What each role may do to a phone. */
const ACTIONS = {
  unlock: { label: 'Unlock (mark paid)', roles: ['owner', 'staff'], status: 'paid', from: ['unpaid'] },
  lock: { label: 'Lock again (mark unpaid)', roles: ['owner'], status: 'unpaid', from: ['paid'] },
  remove: { label: 'Remove the filter', roles: ['owner', 'staff'], status: 'removed', from: ['unpaid', 'paid'] },
};

export function createPortal({ store, auth, setStatus }) {
  const audit = (who, action, extra = {}) =>
    store.addAudit({ at: new Date().toISOString(), by: who.name, role: who.role, action, ...extra });

  function loginPage(url, error = '') {
    const next = url.searchParams.get('next') ?? '';
    return page('Sign in', `<div class="card" style="max-width:24rem;margin:2rem auto">
<h1>Sign in</h1>${error ? `<div class="msg bad">${e(error)}</div>` : ''}
<form method="post" action="/login">
<input type="hidden" name="next" value="${e(next)}">
<label for="u">User name</label><input id="u" name="username" autocomplete="username" required style="width:100%">
<label for="p">Password</label><input id="p" name="password" type="password" autocomplete="current-password" required style="width:100%">
<p><button>Sign in</button></p></form></div>`, null, error ? 401 : 200);
  }

  function deviceCard(d, who, back) {
    const allowed = Object.entries(ACTIONS).filter(([, a]) => a.roles.includes(who.role) && a.from.includes(d.status));
    const forms = allowed.map(([key, a]) => {
      const confirm = key === 'remove'
        ? `<label for="c-${key}">Type the phone code to confirm. This turns the phone back into a normal phone and can't be undone from here.</label><input id="c-${key}" name="confirm" required placeholder="${e(d.payCode)}">`
        : '';
      return `<form method="post" action="/portal/device/${e(d.id)}/${key}" class="card" style="margin:.6rem 0 0">
<input type="hidden" name="back" value="${e(back)}">
<label for="r-${key}">Reason (saved in the action log)</label><input id="r-${key}" name="reason" required maxlength="300" style="width:100%">
${confirm}<p style="margin:.6rem 0 0"><button class="${key === 'remove' ? 'danger' : ''}">${e(a.label)}</button></p></form>`;
    }).join('');
    const hist = (d.history ?? []).slice().reverse().map((h) =>
      `<tr><td>${when(h.at)}</td><td>${pill(h.status)}</td><td>${e(h.by ?? '')}</td><td>${e(h.reason ?? '')}</td></tr>`).join('');
    const pending = d.status === 'removed' && !d.removedSeenAt
      ? '<p class="muted">Waiting for the phone to check in. It does this when it restarts and every 15 minutes while it has internet.</p>'
      : d.removedSeenAt ? `<p class="muted">The phone confirmed the removal at ${when(d.removedSeenAt)}.</p>` : '';
    return `<div class="card"><h1>Phone <code>${e(d.payCode)}</code> ${pill(d.status)}</h1>
<table><tr><th>Model</th><td>${e(d.model || '?')} (Android SDK ${e(d.sdk || '?')})</td></tr>
<tr><th>App version</th><td>${e(d.appVersion || '?')}</td></tr>
<tr><th>Set up</th><td>${when(d.createdAt)}</td></tr>
<tr><th>Paid</th><td>${when(d.paidAt)}</td></tr>
<tr><th>Last check-in</th><td>${when(d.lastSeen)}</td></tr></table>${pending}
${forms || '<p class="muted">Nothing to do for this phone.</p>'}</div>
<div class="card"><h2>History</h2><div class="scroll"><table><tr><th>When</th><th>Status</th><th>By</th><th>Reason</th></tr>${hist}</table></div></div>`;
  }

  async function dashboard(url, who) {
    const all = await store.listDevices();
    const q = (url.searchParams.get('q') ?? '').trim().toUpperCase();
    const status = url.searchParams.get('status') ?? '';
    const day = 86400 * 1000;
    const since = (iso, days) => iso && Date.now() - Date.parse(iso) < days * day;
    const count = (s) => all.filter((d) => d.status === s).length;
    const stats = [
      ['Phones', all.length],
      ['Paid', count('paid')],
      ['Waiting for payment', count('unpaid')],
      ['Filter removed', count('removed')],
      ['Set up this week', all.filter((d) => since(d.createdAt, 7)).length],
      ['Paid in 30 days', all.filter((d) => since(d.paidAt, 30)).length],
    ].map(([k, v]) => `<div class="stat"><span>${e(k)}</span><b>${v}</b></div>`).join('');
    const rows = all
      .filter((d) => (!q || d.payCode.includes(q) || (d.model ?? '').toUpperCase().includes(q)) && (!status || d.status === status))
      .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
      .slice(0, 500)
      .map((d) => `<tr><td><a href="/admin/device/${e(d.id)}"><code>${e(d.payCode)}</code></a></td><td>${pill(d.status)}</td>` +
        `<td>${e(d.model || '?')}</td><td>${when(d.createdAt)}</td><td>${when(d.paidAt)}</td><td>${when(d.lastSeen)}</td></tr>`)
      .join('');
    const opt = (v, l) => `<option value="${v}"${status === v ? ' selected' : ''}>${l}</option>`;
    return page('Dashboard', `${msg(url)}<h1>Dashboard</h1><div class="stats">${stats}</div>
<div class="card"><form class="row" method="get" action="/admin">
<input name="q" value="${e(q)}" placeholder="Phone code or model" aria-label="Search">
<select name="status" aria-label="Status">${opt('', 'All')}${opt('unpaid', 'Waiting for payment')}${opt('paid', 'Paid')}${opt('removed', 'Removed')}</select>
<button>Search</button></form></div>
<div class="card scroll"><table><tr><th>Code</th><th>Status</th><th>Model</th><th>Set up</th><th>Paid</th><th>Last check-in</th></tr>
${rows || '<tr><td colspan="6" class="muted">No phones yet.</td></tr>'}</table></div>`, who);
  }

  async function logsPage(url, who) {
    const logs = await store.listLogs();
    const i = url.searchParams.get('i');
    if (i !== null && logs[Number(i)]) {
      return page('Setup log', `<p><a href="/admin/logs">Back to setup logs</a></p><div class="card"><h1>Setup log, ${when(logs[Number(i)].at)}</h1><pre>${e(logs[Number(i)].text)}</pre></div>`, who);
    }
    const rows = logs.map((l, n) => ({ l, n })).reverse().slice(0, 300).map(({ l, n }) => {
      const first = String(l.text).split('\n').find((x) => /result|error|fail|success/i.test(x)) ?? String(l.text).split('\n')[0];
      return `<tr><td><a href="/admin/logs?i=${n}">${when(l.at)}</a></td><td>${e(first.slice(0, 140))}</td></tr>`;
    }).join('');
    return page('Setup logs', `<h1>Setup logs from the Windows installer</h1><div class="card scroll"><table><tr><th>When</th><th>Summary</th></tr>${rows || '<tr><td colspan="2" class="muted">No logs yet.</td></tr>'}</table></div>`, who);
  }

  async function staffPage(url, who) {
    const users = (await store.listUsers()).sort((a, b) => a.name.localeCompare(b.name));
    const rows = users.map((u) => `<tr><td>${e(u.name)}</td><td>${u.disabled ? '<span class="pill removed">off</span>' : '<span class="pill paid">active</span>'}</td><td>${when(u.createdAt)}</td>
<td><form method="post" action="/admin/staff/${e(u.name)}/${u.disabled ? 'enable' : 'disable'}" style="display:inline"><button class="plain">${u.disabled ? 'Turn on' : 'Turn off'}</button></form>
<form method="post" action="/admin/staff/${e(u.name)}/password" class="row" style="display:inline-flex"><input name="password" type="password" placeholder="New password" aria-label="New password for ${e(u.name)}" autocomplete="new-password"><button class="plain">Set password</button></form></td></tr>`).join('');
    return page('Staff', `${msg(url)}<h1>Staff accounts</h1>
<div class="card"><h2>Add a staff member</h2><p class="muted">Staff can find a phone by the code on its screen, unlock it, or remove the filter. They can't see the list of phones or this page.</p>
<form method="post" action="/admin/staff" class="row"><input name="username" placeholder="user name (a-z, 0-9)" required aria-label="User name" autocomplete="off">
<input name="password" type="password" placeholder="Password (10+ characters)" required aria-label="Password" autocomplete="new-password"><button>Add</button></form></div>
<div class="card scroll"><table><tr><th>User</th><th>Status</th><th>Added</th><th></th></tr>${rows || '<tr><td colspan="4" class="muted">No staff yet.</td></tr>'}</table></div>`, who);
  }

  async function auditPage(who) {
    const rows = (await store.listAudit()).slice(-500).reverse().map((a) =>
      `<tr><td>${when(a.at)}</td><td>${e(a.by)} <span class="muted">(${e(a.role)})</span></td><td>${e(a.action)}</td><td>${a.code ? `<code>${e(a.code)}</code>` : ''}</td><td>${e(a.reason ?? a.detail ?? '')}</td></tr>`).join('');
    return page('Action log', `<h1>Action log</h1><div class="card scroll"><table><tr><th>When</th><th>Who</th><th>What</th><th>Phone</th><th>Reason</th></tr>${rows || '<tr><td colspan="5" class="muted">Nothing yet.</td></tr>'}</table></div>`, who);
  }

  function supportHome(url, who) {
    return page('Support', `${msg(url)}<div class="card" style="max-width:30rem"><h1>Find a phone</h1>
<p class="muted">Ask the customer for the code on their phone's screen (it looks like AOS-7K3D-9QXW).</p>
<form method="get" action="/support/find" class="row"><input name="code" required placeholder="AOS-XXXX-XXXX" aria-label="Phone code" autocomplete="off" style="text-transform:uppercase"><button>Find</button></form></div>`, who);
  }

  async function act(request, who, deviceId, key) {
    const form = await formData(request);
    const a = ACTIONS[key];
    const device = await store.getDevice(deviceId);
    const back = String(form.back ?? '').startsWith('/') && !String(form.back).startsWith('//') ? form.back : '/support';
    const go = (text, bad) => redirect(`${back}${back.includes('?') ? '&' : '?'}${bad ? 'err' : 'ok'}=${encodeURIComponent(text)}`);
    if (!a || !device || !a.roles.includes(who.role)) return go('That action is not allowed.', true);
    if (!a.from.includes(device.status)) return go(`This phone is already ${device.status}.`, true);
    const reason = String(form.reason ?? '').trim().slice(0, 300);
    if (!reason) return go('Please give a reason.', true);
    if (key === 'remove' && String(form.confirm ?? '').trim().toUpperCase() !== device.payCode) {
      return go('The code you typed does not match this phone. Nothing was changed.', true);
    }
    await setStatus(device, a.status, who.name, reason);
    await audit(who, key, { deviceId: device.id, code: device.payCode, reason });
    const done = { unlock: 'Unlocked. The phone opens its apps the next time it checks in (within a minute while it shows the payment screen).', lock: 'Locked again. The phone closes its apps at its next check-in.', remove: 'Removal sent. The phone removes the filter at its next check-in.' }[key];
    return go(done, false);
  }

  /** Returns a Response for portal routes, or null for anything else. */
  async function handle(request, url) {
    const p = url.pathname;
    const method = request.method;
    const portalPath = p === '/login' || p === '/logout' || p === '/admin' || p.startsWith('/admin/') || p === '/support' || p.startsWith('/support/') || p.startsWith('/portal/');
    if (!portalPath) return null;
    if (method === 'POST' && !auth.sameOrigin(request)) return page('Not allowed', '<h1>Not allowed</h1>', null, 403);

    if (p === '/login') {
      if (method === 'GET') return loginPage(url);
      const form = await formData(request);
      const r = await auth.login(form.username, form.password);
      if (r.error) return loginPage(url, r.error === 'locked' ? 'Too many tries. Wait 15 minutes and try again.' : 'Wrong user name or password.');
      await audit(r.user, 'sign-in');
      const next = String(form.next ?? '');
      const safe = /^\/(admin|support)(\/|$|\?)/.test(next) ? next : r.user.role === 'owner' ? '/admin' : '/support';
      return redirect(safe, { 'set-cookie': r.cookie });
    }
    if (p === '/logout') return redirect('/login', { 'set-cookie': await auth.logout(request) });

    const who = await auth.current(request);
    if (!who) return method === 'GET' ? redirect('/login?next=' + encodeURIComponent(p + url.search)) : page('Signed out', '<h1>Please sign in again</h1>', null, 401);

    let m;
    // Support portal: owner and staff.
    if (p === '/support' && method === 'GET') return supportHome(url, who);
    if (p === '/support/find' && method === 'GET') {
      const code = (url.searchParams.get('code') ?? '').trim().toUpperCase().replace(/\s+/g, '');
      const d = /^AOS-[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(code) ? await store.findDevice('payCode', code) : null;
      await audit(who, 'look-up', { code, detail: d ? 'found' : 'not found' });
      if (!d) return redirect('/support?err=' + encodeURIComponent(`No phone has the code ${code || '(empty)'}. Check the code on the phone's screen.`));
      return redirect('/support/phone/' + encodeURIComponent(code));
    }
    if ((m = /^\/support\/phone\/(AOS-[A-Z0-9]{4}-[A-Z0-9]{4})$/.exec(p)) && method === 'GET') {
      const d = await store.findDevice('payCode', m[1]);
      if (!d) return redirect('/support?err=' + encodeURIComponent('Phone not found.'));
      return page('Phone ' + d.payCode, `${msg(url)}<p><a href="/support">Find another phone</a></p>${deviceCard(d, who, p)}`, who);
    }
    if ((m = /^\/portal\/device\/([\w-]+)\/(unlock|lock|remove)$/.exec(p)) && method === 'POST') return act(request, who, m[1], m[2]);

    // Everything else is the owner's dashboard.
    if (who.role !== 'owner') return page('Not allowed', '<h1>Only the owner can open this page</h1><p><a href="/support">Go to the support portal</a></p>', who, 403);
    if (p === '/admin' && method === 'GET') return dashboard(url, who);
    if ((m = /^\/admin\/device\/([\w-]+)$/.exec(p)) && method === 'GET') {
      const d = await store.getDevice(m[1]);
      if (!d) return redirect('/admin?err=' + encodeURIComponent('Phone not found.'));
      return page('Phone ' + d.payCode, `${msg(url)}<p><a href="/admin">All phones</a></p>${deviceCard(d, who, p)}`, who);
    }
    if (p === '/admin/logs' && method === 'GET') return logsPage(url, who);
    if (p === '/admin/audit' && method === 'GET') return auditPage(who);
    if (p === '/admin/staff' && method === 'GET') return staffPage(url, who);
    if (p === '/admin/staff' && method === 'POST') {
      const f = await formData(request);
      const name = String(f.username ?? '').trim().toLowerCase();
      if (!validUsername(name) || name === 'owner') return redirect('/admin/staff?err=' + encodeURIComponent('User names are 3 to 32 letters, numbers, dots, dashes or underscores (and not "owner").'));
      if (await store.getUser(name)) return redirect('/admin/staff?err=' + encodeURIComponent('That user name is taken.'));
      const problem = passwordProblem(f.password);
      if (problem) return redirect('/admin/staff?err=' + encodeURIComponent(problem));
      await store.putUser({ name, role: 'staff', passwordHash: await hashPassword(f.password), createdAt: new Date().toISOString(), disabled: false });
      await audit(who, 'add-staff', { detail: name });
      return redirect('/admin/staff?ok=' + encodeURIComponent(`Added ${name}.`));
    }
    if ((m = /^\/admin\/staff\/([a-z0-9._-]{3,32})\/(enable|disable|password)$/.exec(p)) && method === 'POST') {
      const u = await store.getUser(m[1]);
      if (!u) return redirect('/admin/staff?err=' + encodeURIComponent('No such user.'));
      if (m[2] === 'password') {
        const f = await formData(request);
        const problem = passwordProblem(f.password);
        if (problem) return redirect('/admin/staff?err=' + encodeURIComponent(problem));
        u.passwordHash = await hashPassword(f.password);
      } else {
        u.disabled = m[2] === 'disable';
      }
      await store.putUser(u);
      await audit(who, `staff-${m[2]}`, { detail: u.name });
      return redirect('/admin/staff?ok=' + encodeURIComponent(`Updated ${u.name}.`));
    }
    return page('Not found', '<h1>Page not found</h1>', who, 404);
  }

  return { handle };
}
