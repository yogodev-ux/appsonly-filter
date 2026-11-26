// The staff portal: a separate, simple website for support staff. It has its own look and only
// what staff may do:
//   /staff                    Find a customer by name, phone number or email
//   /staff/customers/<id>     Remove the filter with the customer's removal code, or email the
//                             customer a link to choose a new code
// Staff can't mark phones paid, unlock them, remove without the code, or see sales, staff or logs.
import { escapeHtml as e } from './util.js';
import { ServiceError } from './services.js';
import { banner, formData, htmlPage, redirect, when, withMsg } from './web.js';

const CSS = `
:root{--bg:#f3f8f7;--card:#fff;--text:#13211f;--muted:#566663;--line:#d3e2df;--brand:#0f766e;--brandtext:#fff;--danger:#b3261e;--ok:#15803d}
@media (prefers-color-scheme:dark){:root{--bg:#0f1716;--card:#18211f;--text:#e3eeeb;--muted:#94a8a4;--line:#29403c;--brand:#2bb3a5;--brandtext:#06201d;--danger:#f2706a;--ok:#5cc982}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:17px/1.55 system-ui,-apple-system,Segoe UI,sans-serif}
header{background:var(--brand);color:var(--brandtext);padding:.8rem 16px}
header .in{max-width:40rem;margin:0 auto;display:flex;align-items:center;gap:1rem}
header a{color:inherit;text-decoration:none;font-weight:700;margin-right:auto}
header button{background:none;border:1px solid currentColor;color:inherit;border-radius:99px;padding:.25rem .9rem;font:inherit;font-size:.9rem;cursor:pointer}
main{max-width:40rem;margin:0 auto;padding:1.2rem 16px 3rem}
h1{font-size:1.35rem;margin:.3rem 0 1rem}h2{font-size:1.05rem;margin:0 0 .5rem}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:1rem 1.1rem;margin:0 0 1rem}
.search{display:flex;gap:.5rem}.search input{flex:1;min-width:0}
input{font:inherit;padding:.6rem .8rem;border:1px solid var(--line);border-radius:10px;background:var(--bg);color:var(--text);width:100%}
button.main{font:inherit;font-weight:600;padding:.6rem 1.1rem;border-radius:10px;border:0;background:var(--brand);color:var(--brandtext);cursor:pointer}
button.danger{background:var(--danger);color:#fff}button.soft{background:transparent;color:var(--brand);border:1px solid var(--brand)}
a.result{display:block;text-decoration:none;color:inherit}a.result:hover{border-color:var(--brand)}
main a{color:var(--brand)}
.muted{color:var(--muted)}label{display:block;margin:.7rem 0 .25rem;font-size:.92rem;color:var(--muted)}
dl{display:grid;grid-template-columns:auto 1fr;gap:.35rem 1rem;margin:0}dt{color:var(--muted)}dd{margin:0;overflow-wrap:anywhere}
.tag{display:inline-block;border-radius:99px;padding:0 .6rem;font-size:.82rem;font-weight:600;background:var(--line)}
.msg{padding:.7rem 1rem;border-radius:10px;margin-bottom:1rem;border:1px solid var(--ok);color:var(--ok);background:var(--card)}.msg.bad{border-color:var(--danger);color:var(--danger)}
`;

const STATUS = { unpaid: 'Filter on (not paid yet)', paid: 'Filter on', removed: 'Filter removed' };

export function createStaffPortal({ store, services }) {
  function page(url, who, title, body, status = 200) {
    return htmlPage({
      title: `${title} · Apps Only support`,
      css: CSS,
      status,
      body: `<header><div class="in"><a href="/staff">Apps Only support</a><span>${e(who.name)}</span>` +
        `<form method="post" action="/logout" style="margin:0"><button>Sign out</button></form></div></header>` +
        `<main>${banner(url)}${body}</main>`,
    });
  }

  async function searchPage(url, who) {
    const q = (url.searchParams.get('q') ?? '').trim();
    let results = '';
    if (q) {
      const found = await services.searchCustomers(q);
      results = found.length
        ? found.map((c) => `<a class="card result" href="/staff/customers/${e(c.id)}"><b>${e(c.name)}</b> <span class="tag">${e(STATUS[c.status] ?? c.status)}</span>` +
          `<br><span class="muted">${e(c.phone)} · ${e(c.email)}</span></a>`).join('')
        : `<div class="card">${q.length < 3 ? 'Type at least 3 characters.' : 'No customer found. Check the spelling, or try the phone number or email.'}</div>`;
    }
    return page(url, who, 'Find a customer', `<h1>Find a customer</h1>
<form class="card search" method="get" action="/staff"><input name="q" value="${e(q)}" placeholder="Name, phone number or email" aria-label="Name, phone number or email" autofocus>
<button class="main">Search</button></form>${results}`);
  }

  async function customerPage(url, who, id) {
    const d = await store.getDevice(id);
    if (!d || !d.customer) return page(url, who, 'Not found', '<h1>Customer not found</h1><p><a href="/staff">Back to search</a></p>', 404);
    const c = services.customerView(d);
    let actions;
    if (d.status === 'removed') {
      actions = `<div class="card"><h2>The filter is removed</h2><p class="muted" style="margin:0">${d.removedSeenAt
        ? `The phone got the removal at ${when(d.removedSeenAt)}.`
        : 'The phone will remove it the next time it goes online. Ask the customer to connect it to the internet.'}</p></div>`;
      const code = services.uninstallCodeFor(d);
      if (code && !d.removedSeenAt) {
        actions += `<div class="card"><h2>Phone can't go online?</h2><p class="muted" style="margin-top:0">Read the customer this uninstall code. They tap Remove the filter at the bottom of the phone's home screen and type it there.</p>` +
          `<p style="font-size:1.6rem;letter-spacing:.08em;margin:.3rem 0"><code>${e(code)}</code></p></div>`;
      }
    } else {
      actions = `<form class="card" method="post" action="/staff/customers/${e(d.id)}/remove"><h2>Remove the filter</h2>
<p class="muted" style="margin-top:0">Ask the customer for the removal code they chose at setup. Never tell them a code.</p>
${c.hasRemovalCode ? '' : '<p><b>This phone has not sent its removal code yet</b> (it has not been online since setup). Ask the owner.</p>'}
<label for="code">Customer's removal code</label><input id="code" name="code" type="password" required autocomplete="off" maxlength="64">
<label for="reason">Reason (saved in the log)</label><input id="reason" name="reason" required maxlength="300">
<p style="margin-bottom:0"><button class="main danger">Remove the filter</button></p></form>
<form class="card" method="post" action="/staff/customers/${e(d.id)}/reset"><h2>Customer forgot the code?</h2>
<p class="muted" style="margin-top:0">Email ${e(c.email)} a link to choose a new removal code. The link works once, for 24 hours.</p>
<button class="main soft">Send reset email</button></form>`;
    }
    return page(url, who, c.name, `<p><a href="/staff">Back to search</a></p><h1>${e(c.name)}</h1>
<div class="card"><dl><dt>Phone</dt><dd>${e(c.phone)}</dd><dt>Email</dt><dd>${e(c.email)}</dd>
<dt>Status</dt><dd><span class="tag">${e(STATUS[d.status] ?? d.status)}</span></dd><dt>Set up</dt><dd>${when(d.createdAt)}</dd>
<dt>Phone code</dt><dd><code>${e(d.payCode)}</code></dd></dl></div>${actions}`);
  }

  async function action(request, who, id, kind) {
    const back = `/staff/customers/${encodeURIComponent(id)}`;
    const f = await formData(request);
    try {
      if (kind === 'remove') {
        await services.removeWithCode(who, id, f.code, f.reason);
        return redirect(withMsg(back, 'ok', 'Done. The filter will be removed the next time the phone goes online.'));
      }
      await services.sendCodeReset(who, id);
      return redirect(withMsg(back, 'ok', 'Reset email sent. Ask the customer to check their inbox (and spam folder).'));
    } catch (err) {
      if (err instanceof ServiceError) return redirect(withMsg(back, 'err', err.message));
      throw err;
    }
  }

  async function handle(request, url, who) {
    const p = url.pathname;
    let m;
    if (p === '/staff' && request.method === 'GET') return searchPage(url, who);
    if ((m = /^\/staff\/customers\/([\w-]+)$/.exec(p)) && request.method === 'GET') return customerPage(url, who, m[1]);
    if ((m = /^\/staff\/customers\/([\w-]+)\/(remove|reset)$/.exec(p)) && request.method === 'POST') return action(request, who, m[1], m[2]);
    return page(url, who, 'Not found', '<h1>Page not found</h1><p><a href="/staff">Back to search</a></p>', 404);
  }

  return { handle };
}
