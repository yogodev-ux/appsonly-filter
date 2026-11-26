// The owner portal: the owner's private website. It opens on the alerts that need the owner.
//   /owner                 Alerts (tamper attempts, failed setups, unpaid phones, wrong codes, code resets)
//   /owner/sales           Sales summary
//   /owner/customers       Customer list with details; one customer: mark paid, lock again,
//                          remove without the code, send a code-reset email
//   /owner/staff           Staff accounts: add, turn off or on, set a new password
//   /owner/setup-logs      Logs from the Windows installer
//   /owner/actions         Action log: who did what, when and why
// Staff never see any of this; they have their own portal (staff.js).
import { escapeHtml as e } from './util.js';
import { hashPassword, passwordProblem, validUsername } from './auth.js';
import { ALERT_TYPES, ServiceError } from './services.js';
import { banner, formData, htmlPage, redirect, when, withMsg } from './web.js';

const CSS = `
:root{--bg:#f4f5f8;--card:#fff;--text:#1b2230;--muted:#5c6577;--line:#dde1e8;--side:#1f2a44;--sidetext:#c9d3e6;--accent:#2f5fb3;--danger:#b3261e;--ok:#1b7f3b;--warn:#8a5a00;--warnbg:#fff4dc}
@media (prefers-color-scheme:dark){:root{--bg:#111419;--card:#1b1f26;--text:#e5e8ee;--muted:#98a1b0;--line:#2c323c;--side:#0b1020;--sidetext:#aeb9cf;--accent:#78a2ec;--danger:#f2706a;--ok:#5cc982;--warn:#e7b85a;--warnbg:#2c2414}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif;display:flex;min-height:100vh}
nav{width:13rem;flex:none;background:var(--side);color:var(--sidetext);padding:1rem .7rem;display:flex;flex-direction:column;gap:.15rem}
nav .brand{color:#fff;font-weight:700;padding:.2rem .6rem 1rem}nav .brand small{display:block;font-weight:400;color:var(--sidetext);font-size:.8rem}
nav a{color:var(--sidetext);text-decoration:none;padding:.45rem .6rem;border-radius:6px;display:flex;justify-content:space-between}
nav a:hover{background:rgba(255,255,255,.07)}nav a[aria-current]{background:rgba(255,255,255,.13);color:#fff}
nav .count{background:#d9534f;color:#fff;border-radius:99px;padding:0 .45rem;font-size:.8rem}
nav form{margin-top:auto}nav button{background:none;border:0;color:var(--sidetext);padding:.45rem .6rem;cursor:pointer;font:inherit}
main{flex:1;min-width:0;padding:1.4rem 1.6rem;max-width:68rem}
h1{font-size:1.45rem;margin:0 0 1rem}h2{font-size:1.05rem;margin:0 0 .7rem}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:1rem 1.1rem;margin:0 0 1rem}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(10rem,1fr));gap:.7rem;margin-bottom:1rem}
.stat{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:.75rem 1rem}.stat span{display:block;color:var(--muted);font-size:.85rem}.stat b{font-size:1.7rem}
table{width:100%;border-collapse:collapse;font-size:.92rem}th,td{text-align:left;padding:.5rem;border-bottom:1px solid var(--line);vertical-align:top}th{color:var(--muted);font-weight:600}
.scroll{overflow-x:auto}.muted{color:var(--muted)}code{font-size:.95em}
.pill{display:inline-block;border-radius:99px;padding:0 .55rem;font-size:.8rem;font-weight:600;border:1px solid currentColor;white-space:nowrap}
.paid{color:var(--ok)}.unpaid{color:var(--warn)}.removed{color:var(--muted)}
.alert{display:flex;gap:1rem;align-items:flex-start;justify-content:space-between;border-left:4px solid var(--warn);background:var(--warnbg)}
.alert.tamper,.alert.removal_locked{border-left-color:var(--danger)}
input,select{font:inherit;padding:.45rem .6rem;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--text);max-width:100%}
button{font:inherit;padding:.45rem .95rem;border-radius:6px;border:1px solid var(--accent);background:var(--accent);color:#fff;cursor:pointer}
button.danger{background:var(--danger);border-color:var(--danger)}button.plain{background:transparent;color:var(--accent)}
.row{display:flex;gap:.5rem;flex-wrap:wrap;align-items:center}
label{display:block;margin:.5rem 0 .2rem;color:var(--muted);font-size:.88rem}
.msg{padding:.6rem 1rem;border-radius:8px;margin-bottom:1rem;border:1px solid var(--ok);color:var(--ok);background:var(--card)}.msg.bad{border-color:var(--danger);color:var(--danger)}
.actions{display:grid;grid-template-columns:repeat(auto-fit,minmax(16rem,1fr));gap:.8rem}.actions form{margin:0}
pre{white-space:pre-wrap;word-break:break-word;font-size:.82rem;margin:.5rem 0 0}
details summary{cursor:pointer}
main a{color:var(--accent)}
@media (max-width:48rem){body{flex-direction:column}nav{width:auto;flex-direction:row;flex-wrap:wrap;padding:.5rem 16px}nav .brand{padding:.4rem .6rem;width:100%}nav form{margin:0 0 0 auto}main{padding:1rem 16px}}
`;

const pill = (s) => `<span class="pill ${e(s)}">${e({ unpaid: 'not paid', paid: 'paid', removed: 'filter removed' }[s] ?? s)}</span>`;
const DAY = 86400 * 1000;

export function createOwnerPortal({ store, services, now = () => new Date() }) {
  const audit = (who, action, extra = {}) => store.addAudit({ at: now().toISOString(), by: who.name, role: who.role, action, ...extra });

  async function page(url, who, title, body, status = 200) {
    const open = (await services.listAlerts()).length;
    const links = [
      ['/owner', 'Alerts', open ? `<span class="count">${open}</span>` : ''],
      ['/owner/sales', 'Sales', ''],
      ['/owner/customers', 'Customers', ''],
      ['/owner/staff', 'Staff', ''],
      ['/owner/setup-logs', 'Setup logs', ''],
      ['/owner/actions', 'Action log', ''],
    ];
    const here = url.pathname === '/owner' ? '/owner' : links.slice(1).find(([h]) => url.pathname.startsWith(h))?.[0];
    const nav = links.map(([h, t, extra]) => `<a href="${h}"${h === here ? ' aria-current="page"' : ''}><span>${t}</span>${extra}</a>`).join('');
    return htmlPage({
      title: `${title} · Apps Only owner`,
      css: CSS,
      status,
      body: `<nav aria-label="Owner portal"><div class="brand">Apps Only<small>Owner portal</small></div>${nav}` +
        `<form method="post" action="/logout"><button>Sign out</button></form></nav><main>${banner(url)}${body}</main>`,
    });
  }

  // ---- Alerts (home) ----
  async function alertsPage(url, who) {
    const alerts = await services.listAlerts();
    const devices = new Map((await store.listDevices()).map((d) => [d.id, d]));
    const items = alerts.map((a) => {
      const d = a.deviceId ? devices.get(a.deviceId) : null;
      const whoLine = d ? `<a href="/owner/customers/${e(d.id)}">${e(d.customer?.name || d.payCode)}</a> <span class="muted">· ${e(d.payCode)}</span>` : '<span class="muted">Before the phone was linked</span>';
      return `<div class="card alert ${e(a.type)}"><div><b>${e(ALERT_TYPES[a.type] ?? a.type)}</b><br>${whoLine}` +
        `${a.detail ? `<br><span>${e(a.type === 'tamper' ? a.detail.replace(/_/g, ' ') : a.detail)}</span>` : ''}<br><span class="muted">${when(a.at)}</span></div>` +
        `<form method="post" action="/owner/alerts/${e(a.id)}/handled"><button class="plain">Mark handled</button></form></div>`;
    }).join('');
    return page(url, who, 'Alerts', `<h1>Alerts that need you</h1>${items || '<div class="card">Nothing needs you right now.</div>'}` +
      `<p><a href="/owner/actions">See handled alerts in the action log</a></p>`);
  }

  // ---- Sales ----
  async function salesPage(url, who) {
    const all = await store.listDevices();
    const t = now().getTime();
    const within = (iso, days) => iso && t - Date.parse(iso) < days * DAY;
    const monthStart = new Date(now().getFullYear(), now().getMonth(), 1).getTime();
    const paid = all.filter((d) => d.paidAt);
    const stats = [
      ['Paid this month', paid.filter((d) => Date.parse(d.paidAt) >= monthStart).length],
      ['Paid in 30 days', paid.filter((d) => within(d.paidAt, 30)).length],
      ['Paid in total', paid.length],
      ['Set up, not paid yet', all.filter((d) => d.status === 'unpaid').length],
      ['Set up this week', all.filter((d) => within(d.createdAt, 7)).length],
      ['Filter removed', all.filter((d) => d.status === 'removed').length],
    ].map(([k, v]) => `<div class="stat"><span>${e(k)}</span><b>${v}</b></div>`).join('');
    const recent = paid.sort((a, b) => b.paidAt.localeCompare(a.paidAt)).slice(0, 25).map((d) =>
      `<tr><td>${when(d.paidAt)}</td><td><a href="/owner/customers/${e(d.id)}">${e(d.customer?.name || '(no details yet)')}</a></td>` +
      `<td><code>${e(d.payCode)}</code></td><td>${e(d.history?.findLast?.((h) => h.status === 'paid')?.by ?? '')}</td></tr>`).join('');
    return page(url, who, 'Sales', `<h1>Sales</h1><div class="stats">${stats}</div>
<div class="card"><h2>Latest payments</h2><div class="scroll"><table><tr><th>Paid</th><th>Customer</th><th>Phone code</th><th>How</th></tr>
${recent || '<tr><td colspan="4" class="muted">No payments yet.</td></tr>'}</table></div></div>`);
  }

  // ---- Customers ----
  async function customersPage(url, who) {
    const q = (url.searchParams.get('q') ?? '').trim();
    const status = url.searchParams.get('status') ?? '';
    const ql = q.toLowerCase();
    const qd = q.replace(/\D/g, '');
    const rows = (await store.listDevices())
      .filter((d) => !status || d.status === status)
      .filter((d) => {
        if (!ql) return true;
        const c = d.customer ?? {};
        return d.payCode.toLowerCase().includes(ql) || (c.name ?? '').toLowerCase().includes(ql) ||
          (c.email ?? '').includes(ql) || (qd.length >= 4 && (c.phone ?? '').replace(/\D/g, '').includes(qd));
      })
      .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
      .slice(0, 500)
      .map((d) => `<tr><td><a href="/owner/customers/${e(d.id)}">${e(d.customer?.name || '(no details yet)')}</a></td>` +
        `<td>${e(d.customer?.phone ?? '')}</td><td>${e(d.customer?.email ?? '')}</td><td>${pill(d.status)}</td>` +
        `<td><code>${e(d.payCode)}</code></td><td>${when(d.createdAt)}</td></tr>`).join('');
    const opt = (v, l) => `<option value="${v}"${status === v ? ' selected' : ''}>${l}</option>`;
    return page(url, who, 'Customers', `<h1>Customers</h1>
<div class="card"><form class="row" method="get" action="/owner/customers">
<input name="q" value="${e(q)}" placeholder="Name, phone, email or phone code" aria-label="Search" style="flex:1;min-width:12rem">
<select name="status" aria-label="Status">${opt('', 'All')}${opt('unpaid', 'Not paid')}${opt('paid', 'Paid')}${opt('removed', 'Filter removed')}</select>
<button>Search</button></form></div>
<div class="card scroll"><table><tr><th>Name</th><th>Phone</th><th>Email</th><th>Status</th><th>Phone code</th><th>Set up</th></tr>
${rows || '<tr><td colspan="6" class="muted">No customers found.</td></tr>'}</table></div>`);
  }

  function reasonField(id) {
    return `<label for="${id}">Reason (saved in the action log)</label><input id="${id}" name="reason" required maxlength="300" style="width:100%">`;
  }

  async function customerPage(url, who, id) {
    const d = await store.getDevice(id);
    if (!d) return page(url, who, 'Not found', '<h1>Customer not found</h1>', 404);
    const v = services.customerView(d);
    const forms = [];
    if (d.status === 'unpaid') {
      forms.push(`<form method="post" action="/owner/customers/${e(d.id)}/paid" class="card"><h2>Mark paid</h2>
<p class="muted">Unlocks the phone, for a payment made outside the app.</p>${reasonField('r-paid')}<p><button>Mark paid</button></p></form>`);
    }
    if (d.status === 'paid') {
      forms.push(`<form method="post" action="/owner/customers/${e(d.id)}/lock" class="card"><h2>Lock again</h2>
<p class="muted">Back to block-all until paid (for example after a refund).</p>${reasonField('r-lock')}<p><button>Lock again</button></p></form>`);
    }
    if (d.status !== 'removed') {
      forms.push(`<form method="post" action="/owner/customers/${e(d.id)}/remove" class="card"><h2>Remove without the code</h2>
<p class="muted">Owner only. Turns the phone back into a normal phone. This can't be undone from here.</p>${reasonField('r-remove')}
<label for="c-remove">Type the phone code <code>${e(d.payCode)}</code> to confirm</label><input id="c-remove" name="confirm" required autocomplete="off" style="width:100%">
<p><button class="danger">Remove the filter</button></p></form>`);
      if (v.email) {
        forms.push(`<form method="post" action="/owner/customers/${e(d.id)}/reset" class="card"><h2>Removal code reset</h2>
<p class="muted">Emails ${e(v.email)} a one-time link (24 hours) to choose a new removal code.</p><p><button class="plain">Send reset email</button></p></form>`);
      }
    }
    const removal = d.status !== 'removed' ? ''
      : d.removedSeenAt ? `<p>The phone got the removal at ${when(d.removedSeenAt)}.</p>`
        : '<p class="muted">Waiting for the phone to go online and get the removal.</p>';
    const hist = (d.history ?? []).slice().reverse().map((h) =>
      `<tr><td>${when(h.at)}</td><td>${pill(h.status)}</td><td>${e(h.by ?? '')}</td><td>${e(h.reason ?? '')}</td></tr>`).join('');
    return page(url, who, v.name || d.payCode, `<p><a href="/owner/customers">All customers</a></p>
<h1>${e(v.name || '(no details yet)')} ${pill(d.status)}</h1>
<div class="card"><table>
<tr><th>Phone number</th><td>${e(v.phone || '-')}</td></tr><tr><th>Email</th><td>${e(v.email || '-')}</td></tr>
<tr><th>Removal code</th><td>${v.hasRemovalCode ? 'Set' : '<span class="muted">Not received yet</span>'}</td></tr>
<tr><th>Phone code</th><td><code>${e(d.payCode)}</code></td></tr>
<tr><th>Phone model</th><td>${e(d.model || '?')} (Android SDK ${e(d.sdk || '?')}), app ${e(d.appVersion || '?')}</td></tr>
<tr><th>Set up</th><td>${when(d.createdAt)}</td></tr><tr><th>Paid</th><td>${when(d.paidAt)}</td></tr>
<tr><th>Last online</th><td>${when(d.lastSeen)}</td></tr></table>${removal}</div>
<div class="actions">${forms.join('')}</div>
<div class="card"><h2>History</h2><div class="scroll"><table><tr><th>When</th><th>Status</th><th>By</th><th>Reason</th></tr>${hist}</table></div></div>`);
  }

  async function customerAction(request, who, id, action) {
    const back = `/owner/customers/${encodeURIComponent(id)}`;
    const f = await formData(request);
    try {
      if (action === 'paid') await services.setPaid(who, id, true, f.reason);
      else if (action === 'lock') await services.setPaid(who, id, false, f.reason);
      else if (action === 'remove') {
        const d = await store.getDevice(id);
        if (!d) throw new ServiceError('Phone not found.');
        if (String(f.confirm ?? '').trim().toUpperCase() !== d.payCode) throw new ServiceError("The phone code you typed doesn't match. Nothing was changed.");
        await services.removeOverride(who, id, f.reason);
      } else if (action === 'reset') await services.sendCodeReset(who, id);
      else return new Response('Not found', { status: 404 });
    } catch (err) {
      if (err instanceof ServiceError) return redirect(withMsg(back, 'err', err.message));
      throw err;
    }
    const done = { paid: 'Marked paid. The phone unlocks next time it goes online.', lock: 'Locked again.', remove: 'The filter will be removed next time the phone goes online.', reset: 'Reset email sent.' }[action];
    return redirect(withMsg(back, 'ok', done));
  }

  // ---- Staff ----
  async function staffPage(url, who) {
    const users = (await store.listUsers()).filter((u) => u.role === 'staff').sort((a, b) => a.name.localeCompare(b.name));
    const rows = users.map((u) => `<tr><td><b>${e(u.name)}</b></td><td>${u.disabled ? '<span class="pill removed">off</span>' : '<span class="pill paid">on</span>'}</td>
<td>${when(u.createdAt)}</td><td><div class="row">
<form method="post" action="/owner/staff/${e(u.name)}/${u.disabled ? 'on' : 'off'}"><button class="plain">${u.disabled ? 'Turn on' : 'Turn off'}</button></form>
<form method="post" action="/owner/staff/${e(u.name)}/password" class="row"><input name="password" type="password" placeholder="New password" aria-label="New password for ${e(u.name)}" autocomplete="new-password" required minlength="10"><button class="plain">Set password</button></form>
</div></td></tr>`).join('');
    return page(url, who, 'Staff', `<h1>Staff accounts</h1>
<div class="card"><p class="muted" style="margin-top:0">Staff sign in on the same page as you, and see only the staff portal: find a customer, remove the filter with the customer's removal code, and send a code-reset email.</p>
<div class="scroll"><table><tr><th>User name</th><th>Access</th><th>Added</th><th></th></tr>${rows || '<tr><td colspan="4" class="muted">No staff yet.</td></tr>'}</table></div></div>
<form method="post" action="/owner/staff" class="card"><h2>Add a staff member</h2>
<label for="sn">User name (3 to 32 letters, digits, dots, dashes)</label><input id="sn" name="username" required pattern="[a-z0-9._\\-]{3,32}" autocomplete="off">
<label for="sp">Password (at least 10 characters)</label><input id="sp" name="password" type="password" required minlength="10" autocomplete="new-password">
<p><button>Add</button></p></form>`);
  }

  async function staffAction(request, who, name, action) {
    const f = await formData(request);
    const back = '/owner/staff';
    if (action === 'add') {
      const n = String(f.username ?? '').trim().toLowerCase();
      if (!validUsername(n) || n === 'owner') return redirect(withMsg(back, 'err', 'Pick a user name of 3 to 32 letters, digits, dots or dashes (not "owner").'));
      if (await store.getUser(n)) return redirect(withMsg(back, 'err', 'That user name is taken.'));
      const bad = passwordProblem(f.password);
      if (bad) return redirect(withMsg(back, 'err', bad));
      await store.putUser({ name: n, role: 'staff', passwordHash: await hashPassword(f.password), disabled: false, createdAt: now().toISOString() });
      await audit(who, 'staff-added', { detail: n });
      return redirect(withMsg(back, 'ok', `Added ${n}.`));
    }
    const u = await store.getUser(name);
    if (!u || u.role !== 'staff') return redirect(withMsg(back, 'err', 'Staff member not found.'));
    if (action === 'off' || action === 'on') {
      u.disabled = action === 'off';
      await store.putUser(u);
      await audit(who, action === 'off' ? 'staff-turned-off' : 'staff-turned-on', { detail: u.name });
      return redirect(withMsg(back, 'ok', `${u.name} is turned ${action}.`));
    }
    if (action === 'password') {
      const bad = passwordProblem(f.password);
      if (bad) return redirect(withMsg(back, 'err', bad));
      u.passwordHash = await hashPassword(f.password);
      await store.putUser(u);
      await audit(who, 'staff-password-set', { detail: u.name });
      return redirect(withMsg(back, 'ok', `New password set for ${u.name}.`));
    }
    return new Response('Not found', { status: 404 });
  }

  // ---- Logs ----
  async function setupLogsPage(url, who) {
    const logs = (await store.listLogs()).slice(-200).reverse();
    const items = logs.map((l) => {
      const result = /RESULT\s+(\w+)/.exec(l.text)?.[1] ?? 'unknown';
      const err = l.text.split('\n').find((x) => x.startsWith('ERROR'))?.replace(/^ERROR\s*/, '') ?? '';
      const cls = result === 'ok' || result === 'success' ? 'paid' : result === 'failed' ? 'unpaid' : 'removed';
      return `<div class="card"><details><summary>${when(l.at)} <span class="pill ${cls}">${e(result)}</span> ${e(err)}</summary><pre>${e(l.text)}</pre></details></div>`;
    }).join('');
    return page(url, who, 'Setup logs', `<h1>Setup logs</h1><p class="muted">Sent by the Windows installer after each setup. They hold no customer details.</p>${items || '<div class="card">No setup logs yet.</div>'}`);
  }

  const ACTION_NAMES = {
    remove: 'Removed the filter (with code)', 'remove-without-code': 'Removed the filter without the code', 'wrong-removal-code': 'Wrong removal code',
    'mark-paid': 'Marked paid', 'lock-again': 'Locked again', 'send-code-reset': 'Sent a code-reset email', 'new-removal-code': 'Customer set a new removal code',
    'alert-handled': 'Marked an alert handled', 'staff-added': 'Added staff', 'staff-turned-off': 'Turned staff off', 'staff-turned-on': 'Turned staff on', 'staff-password-set': 'Set a staff password',
  };

  async function actionsPage(url, who) {
    const rows = (await store.listAudit()).slice(-500).reverse().map((a) =>
      `<tr><td>${when(a.at)}</td><td>${e(a.by)} <span class="muted">${e(a.role ?? '')}</span></td><td>${e(ACTION_NAMES[a.action] ?? a.action)}</td>` +
      `<td>${a.deviceId ? `<a href="/owner/customers/${e(a.deviceId)}"><code>${e(a.code ?? '')}</code></a>` : e(a.code ?? '')}</td><td>${e(a.reason ?? a.detail ?? '')}</td></tr>`).join('');
    return page(url, who, 'Action log', `<h1>Action log</h1><div class="card scroll"><table><tr><th>When</th><th>Who</th><th>What</th><th>Phone</th><th>Reason / detail</th></tr>
${rows || '<tr><td colspan="5" class="muted">Nothing yet.</td></tr>'}</table></div>`);
  }

  async function handle(request, url, who) {
    const p = url.pathname;
    const get = request.method === 'GET';
    const post = request.method === 'POST';
    let m;
    if (p === '/owner' && get) return alertsPage(url, who);
    if ((m = /^\/owner\/alerts\/([\w-]+)\/handled$/.exec(p)) && post) {
      try {
        await services.handleAlert(who, m[1]);
      } catch (err) {
        if (err instanceof ServiceError) return redirect(withMsg('/owner', 'err', err.message));
        throw err;
      }
      return redirect(withMsg('/owner', 'ok', 'Marked handled.'));
    }
    if (p === '/owner/sales' && get) return salesPage(url, who);
    if (p === '/owner/customers' && get) return customersPage(url, who);
    if ((m = /^\/owner\/customers\/([\w-]+)$/.exec(p)) && get) return customerPage(url, who, m[1]);
    if ((m = /^\/owner\/customers\/([\w-]+)\/(paid|lock|remove|reset)$/.exec(p)) && post) return customerAction(request, who, m[1], m[2]);
    if (p === '/owner/staff' && get) return staffPage(url, who);
    if (p === '/owner/staff' && post) return staffAction(request, who, '', 'add');
    if ((m = /^\/owner\/staff\/([a-z0-9._-]{3,32})\/(on|off|password)$/.exec(p)) && post) return staffAction(request, who, m[1], m[2]);
    if (p === '/owner/setup-logs' && get) return setupLogsPage(url, who);
    if (p === '/owner/actions' && get) return actionsPage(url, who);
    return page(url, who, 'Not found', '<h1>Page not found</h1>', 404);
  }

  return { handle };
}
