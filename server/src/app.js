// The Apps Only server. Plain Web Fetch API: handle(Request) -> Response.
//
// Phone (app):
//   POST /v1/activate        {installId, model, sdk, appVersion} -> {deviceId, token, payCode, payUrl, license}
//   GET  /v1/license         Bearer <token>                       -> {status, license, payCode, payUrl}
//   POST /v1/customer        Bearer <token> {customer: {name, phone, email, removalCode}}
//                            the details the customer typed in the Windows installer; the removal
//                            code is stored only as a hash
//   POST /v1/tamper          Bearer <token> {events: [{type, detail}]}  -> alerts for the owner
// Customer (from the reset email):
//   GET/POST /reset/<token>  choose a new removal code
// Customer (any browser, e.g. on a PC, since the phone can't browse):
//   GET  /pay/<code>         page with the price and a Pay button
//   GET  /pay/<code>/go      starts the payment (Stripe Checkout, or the test page)
//   POST /pay/<code>/test    test mode only: marks the device paid
//   POST /v1/stripe/webhook  Stripe tells us a payment went through
// Windows installer:
//   POST /v1/installer-logs  text/plain setup log
// Staff (Bearer ADMIN_TOKEN; the support portal in step 5 builds on these):
//   GET  /v1/admin/devices
//   POST /v1/admin/devices/<id>/status {status: "paid" | "unpaid" | "removed"}
//   GET  /v1/admin/installer-logs
import { hashSecret } from './secrets.js';
import { createAuth } from './auth.js';
import { createServices, OutboxMailer } from './services.js';
import { escapeHtml, html, json, payCode, randomToken, sha256Hex, timingSafeEqual } from './util.js';

const STATUSES = new Set(['unpaid', 'paid', 'removed']);
const MAX_LOG = 256 * 1024;

export function createApp({ store, signer, payments, adminToken, baseUrl, priceText = '', ownerPassword = '', mailer = new OutboxMailer(), clock }) {
  const now = () => new Date().toISOString();

  async function licenseFor(device) {
    return signer.sign({ v: 1, deviceId: device.id, status: device.status, issuedAt: Date.now() });
  }

  async function deviceFromAuth(request) {
    const m = /^Bearer (.+)$/.exec(request.headers.get('authorization') ?? '');
    if (!m) return null;
    return store.findDevice('tokenHash', await sha256Hex(m[1]));
  }

  function isAdmin(request) {
    const m = /^Bearer (.+)$/.exec(request.headers.get('authorization') ?? '');
    return Boolean(adminToken && m && timingSafeEqual(m[1], adminToken));
  }

  async function setStatus(device, status, by, reason = '') {
    device.status = status;
    device.history = [...(device.history ?? []), { at: now(), status, by, ...(reason ? { reason } : {}) }];
    if (status === 'paid' && !device.paidAt) device.paidAt = now();
    if (status === 'removed') delete device.removedSeenAt;
    await store.putDevice(device);
  }

  const auth = createAuth({ store, baseUrl, ownerPassword });
  const services = createServices({ store, setStatus, mailer, baseUrl, ...(clock ? { now: clock } : {}) });

  const payUrl = (d) => `${baseUrl}/pay/${d.payCode}`;

  async function activate(request) {
    const body = await request.json().catch(() => null);
    const installId = typeof body?.installId === 'string' ? body.installId.slice(0, 100) : '';
    if (installId.length < 16) return json({ error: 'installId required' }, 400);
    const token = randomToken();
    let device = await store.findDevice('installId', installId);
    if (!device) {
      device = {
        id: 'dev_' + randomToken(12),
        installId,
        payCode: payCode(),
        status: 'unpaid',
        createdAt: now(),
        history: [{ at: now(), status: 'unpaid', by: 'activate' }],
      };
    }
    // A new token each time; the old one stops working.
    device.tokenHash = await sha256Hex(token);
    device.model = String(body.model ?? '').slice(0, 100);
    device.sdk = Number(body.sdk) || 0;
    device.appVersion = String(body.appVersion ?? '').slice(0, 40);
    device.lastSeen = now();
    await store.putDevice(device);
    return json({ deviceId: device.id, token, payCode: device.payCode, payUrl: payUrl(device), status: device.status, license: await licenseFor(device) });
  }

  async function license(request) {
    const device = await deviceFromAuth(request);
    if (!device) return json({ error: 'unknown device' }, 401);
    device.lastSeen = now();
    // The phone has now been told to remove the filter; the portals show that.
    if (device.status === 'removed' && !device.removedSeenAt) device.removedSeenAt = now();
    await store.putDevice(device);
    return json({ status: device.status, payCode: device.payCode, payUrl: payUrl(device), license: await licenseFor(device) });
  }

  function cleanCustomer(c) {
    if (!c || typeof c !== 'object') return null;
    const name = String(c.name ?? '').trim();
    const phone = String(c.phone ?? '').trim();
    const email = String(c.email ?? '').trim().toLowerCase();
    const code = String(c.removalCode ?? '');
    const digits = phone.replace(/\D/g, '').length;
    if (name.length < 2 || name.length > 100) return null;
    if (digits < 7 || digits > 15 || phone.length > 25) return null;
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]{2,}$/.test(email) || email.length > 200) return null;
    if (code.length < 6 || code.length > 64 || /\s/.test(code)) return null;
    return { name, phone, email, code };
  }

  async function customer(request) {
    const device = await deviceFromAuth(request);
    if (!device) return json({ error: 'unknown device' }, 401);
    const body = await request.json().catch(() => null);
    const c = cleanCustomer(body?.customer);
    if (!c) return json({ error: 'bad customer details' }, 400);
    // Set once, at install. Later changes go through the email reset (portal step).
    if (device.removalCodeHash) return json({ ok: true, already: true });
    device.customer = { name: c.name, phone: c.phone, email: c.email, savedAt: now() };
    device.removalCodeHash = await hashSecret(c.code);
    device.lastSeen = now();
    await store.putDevice(device);
    return json({ ok: true });
  }

  async function tamper(request) {
    const device = await deviceFromAuth(request);
    if (!device) return json({ error: 'unknown device' }, 401);
    const body = await request.json().catch(() => null);
    const events = Array.isArray(body?.events) ? body.events.slice(0, 50) : [];
    if (!events.length) return json({ error: 'no events' }, 400);
    const kinds = [...new Set(events.map((e) => String(e?.type ?? 'unknown').slice(0, 40)))];
    await services.addAlert('tamper', device, kinds.join(', '));
    return json({ ok: true });
  }

  async function payPage(code, url) {
    const device = await store.findDevice('payCode', code);
    if (!device) return html('<h1>Code not found</h1><p>Check the code shown on the phone and try again.</p>', 404);
    if (device.status === 'paid') {
      return html(`<h1>Paid, thank you</h1><p>Phone <code>${escapeHtml(code)}</code> is paid. On the phone, tap <b>Check again</b> (or wait a few minutes).</p>`);
    }
    const done = url.searchParams.get('done') ? '<p><b>Payment received.</b> It can take a minute to show up.</p>' : '';
    return html(`<h1>Apps Only</h1>${done}<p>Phone code <code>${escapeHtml(code)}</code></p>` +
      `<p>One-time payment${priceText ? ': <b>' + escapeHtml(priceText) + '</b>' : ''}. No subscription.</p>` +
      `<p><a href="/pay/${encodeURIComponent(code)}/go"><button>Pay now</button></a></p>`);
  }

  async function payGo(code) {
    const device = await store.findDevice('payCode', code);
    if (!device) return html('<h1>Code not found</h1>', 404);
    if (device.status === 'paid') return Response.redirect(payUrl(device), 303);
    if (payments.name === 'test') {
      return html(`<h1>Test payment</h1><p>This server is in test mode. No money is taken.</p>` +
        `<form method="post" action="/pay/${encodeURIComponent(code)}/test"><button>Mark as paid (test)</button></form>`);
    }
    return Response.redirect(await payments.checkoutUrl(device, baseUrl), 303);
  }

  async function payTest(code) {
    if (payments.name !== 'test') return json({ error: 'not in test mode' }, 404);
    const device = await store.findDevice('payCode', code);
    if (!device) return html('<h1>Code not found</h1>', 404);
    if (device.status === 'unpaid') await setStatus(device, 'paid', 'test-payment');
    return Response.redirect(payUrl(device), 303);
  }

  async function webhook(request) {
    const deviceId = await payments.parseWebhook(request);
    if (!deviceId) return json({ ok: false }, 400);
    const device = await store.getDevice(deviceId);
    if (device && device.status === 'unpaid') await setStatus(device, 'paid', 'stripe');
    return json({ ok: true });
  }

  async function installerLog(request) {
    const text = await request.text();
    if (!text || text.length > MAX_LOG) return json({ error: 'bad log' }, 400);
    await store.addLog({ at: now(), text });
    if (/RESULT failed/.test(text)) {
      const why = text.split('\n').find((l) => l.startsWith('ERROR')) ?? 'see the setup log';
      await services.addAlert('setup_failed', null, why.replace(/^ERROR\s*/, '').slice(0, 200));
    }
    return json({ ok: true });
  }

  async function handle(request) {
    const url = new URL(request.url);
    const p = url.pathname;
    const method = request.method;
    try {
      if (p === '/health') return json({ ok: true });
      if (p === '/v1/activate' && method === 'POST') return activate(request);
      if (p === '/v1/license' && method === 'GET') return license(request);
      if (p === '/v1/customer' && method === 'POST') return customer(request);
      if (p === '/v1/tamper' && method === 'POST') return tamper(request);
      if (p === '/v1/stripe/webhook' && method === 'POST') return webhook(request);
      if (p === '/v1/installer-logs' && method === 'POST') return installerLog(request);

      let m;
      if ((m = /^\/reset\/([A-Za-z0-9_-]{20,100})$/.exec(p))) {
        if (method === 'GET') return services.resetPage(m[1]);
        if (method === 'POST') return services.resetSubmit(m[1], Object.fromEntries(new URLSearchParams(await request.text())));
      }
      if ((m = /^\/pay\/([A-Z0-9-]{6,20})$/.exec(p)) && method === 'GET') return payPage(m[1], url);
      if ((m = /^\/pay\/([A-Z0-9-]{6,20})\/go$/.exec(p)) && method === 'GET') return payGo(m[1]);
      if ((m = /^\/pay\/([A-Z0-9-]{6,20})\/test$/.exec(p))) {
        return method === 'POST' ? payTest(m[1]) : payGo(m[1]);
      }

      if (p.startsWith('/v1/admin/')) {
        if (!isAdmin(request)) return json({ error: 'not allowed' }, 401);
        if (p === '/v1/admin/devices' && method === 'GET') {
          const devices = (await store.listDevices()).map(({ tokenHash, installId, removalCodeHash, ...d }) => ({ ...d, hasRemovalCode: Boolean(removalCodeHash) }));
          return json({ devices });
        }
        if (p === '/v1/admin/installer-logs' && method === 'GET') return json({ logs: await store.listLogs() });
        if ((m = /^\/v1\/admin\/devices\/([\w-]+)\/status$/.exec(p)) && method === 'POST') {
          const body = await request.json().catch(() => ({}));
          if (!STATUSES.has(body.status)) return json({ error: 'bad status' }, 400);
          const device = await store.getDevice(m[1]);
          if (!device) return json({ error: 'not found' }, 404);
          await setStatus(device, body.status, 'admin');
          return json({ ok: true, status: device.status });
        }
      }
      return json({ error: 'not found' }, 404);
    } catch (e) {
      console.error(e);
      return json({ error: 'server error' }, 500);
    }
  }

  return { handle, services, auth };
}
