// What the owner portal and the staff portal do, without any web pages (those come in parts 3
// and 4). Every change is written to the action log with who did it and why.
//
//   Customers  searchCustomers(query)  customerView(device)
//   Removal    removeWithCode(who, deviceId, code, reason)   staff and owner: needs the customer's code
//              removeOverride(who, deviceId, reason)         owner only: no code
//   Payment    setPaid(who, deviceId, paid, reason)          owner only
//   Code reset sendCodeReset(who, deviceId)  -> emails a link; resetPage / resetSubmit handle it
//   Alerts     addAlert(type, device, detail)  listAlerts({open})  handleAlert(who, id)
//              checkUnpaid()  (phones set up but not paid for 3 days)
import { checkSecret, hashSecret } from './secrets.js';
import { escapeHtml, html, randomToken, sha256Hex } from './util.js';

export const ALERT_TYPES = {
  tamper: 'Tamper attempt',
  setup_failed: 'Setup failed',
  unpaid: 'Set up but not paid for 3 days',
  removal_locked: 'Too many wrong removal codes',
  code_reset: 'Removal code reset requested',
};

const MAX_WRONG_CODES = 5;
const WRONG_CODE_LOCK_MS = 60 * 60 * 1000;
const RESET_HOURS = 24;
const UNPAID_DAYS = 3;

export class ServiceError extends Error {}

export function createServices({ store, setStatus, mailer, baseUrl, now = () => new Date() }) {
  const iso = () => now().toISOString();

  const audit = (who, action, extra = {}) =>
    store.addAudit({ at: iso(), by: who.name, role: who.role, action, ...extra });

  async function device(id) {
    const d = await store.getDevice(id);
    if (!d) throw new ServiceError('Phone not found.');
    return d;
  }

  function ownerOnly(who) {
    if (who.role !== 'owner') throw new ServiceError('Only the owner can do this.');
  }

  function needReason(reason) {
    const r = String(reason ?? '').trim().slice(0, 300);
    if (!r) throw new ServiceError('Please give a reason.');
    return r;
  }

  /** What a portal may show about a phone. Never the token or the code hash. */
  function customerView(d) {
    return {
      id: d.id,
      payCode: d.payCode,
      status: d.status,
      name: d.customer?.name ?? '',
      phone: d.customer?.phone ?? '',
      email: d.customer?.email ?? '',
      hasRemovalCode: Boolean(d.removalCodeHash),
      hasUninstallCode: Boolean(d.uninstallCode),
      model: d.model ?? '',
      createdAt: d.createdAt,
      paidAt: d.paidAt ?? null,
      lastSeen: d.lastSeen ?? null,
      removedSeenAt: d.removedSeenAt ?? null,
      history: d.history ?? [],
    };
  }

  /**
   * The phone's one-time uninstall code, for a phone that can't go online. Only shown once the
   * filter was removed (with the customer's code, or by the owner), so it never skips that check.
   */
  function uninstallCodeFor(d) {
    return d.status === 'removed' ? d.uninstallCode ?? '' : '';
  }

  /** Name, phone number (digits only are compared) or email; at least 3 characters. */
  async function searchCustomers(query) {
    const q = String(query ?? '').trim().toLowerCase();
    if (q.length < 3) return [];
    const qDigits = q.replace(/\D/g, '');
    return (await store.listDevices())
      .filter((d) => {
        const c = d.customer;
        if (!c) return false;
        if (c.name.toLowerCase().includes(q) || c.email.includes(q)) return true;
        return qDigits.length >= 4 && c.phone.replace(/\D/g, '').includes(qDigits);
      })
      .slice(0, 50)
      .map(customerView);
  }

  async function addAlert(type, d, detail = '') {
    // One open alert per phone and type is enough.
    const open = (await store.listAlerts()).find((a) => !a.handledAt && a.type === type && a.deviceId === (d?.id ?? null));
    if (open) return open;
    const alert = { id: 'al_' + randomToken(9), type, deviceId: d?.id ?? null, payCode: d?.payCode ?? '', detail: String(detail).slice(0, 500), at: iso() };
    await store.addAlert(alert);
    return alert;
  }

  async function listAlerts({ open = true } = {}) {
    return (await store.listAlerts()).filter((a) => (open ? !a.handledAt : true)).slice().reverse();
  }

  async function handleAlert(who, id) {
    ownerOnly(who);
    const a = (await store.listAlerts()).find((x) => x.id === id);
    if (!a) throw new ServiceError('Alert not found.');
    a.handledAt = iso();
    a.handledBy = who.name;
    await store.putAlert(a);
    await audit(who, 'alert-handled', { code: a.payCode, detail: ALERT_TYPES[a.type] ?? a.type });
  }

  async function checkUnpaid() {
    const cutoff = now().getTime() - UNPAID_DAYS * 86400 * 1000;
    let n = 0;
    for (const d of await store.listDevices()) {
      if (d.status === 'unpaid' && Date.parse(d.createdAt) < cutoff) {
        await addAlert('unpaid', d, `Set up ${String(d.createdAt).slice(0, 10)}`);
        n++;
      }
    }
    return n;
  }

  async function removeWithCode(who, deviceId, code, reason) {
    const r = needReason(reason);
    const d = await device(deviceId);
    if (d.status === 'removed') throw new ServiceError('The filter is already removed from this phone.');
    if (!d.removalCodeHash) throw new ServiceError("This phone has no removal code yet (it hasn't been online since setup). Ask the owner.");
    const t = now().getTime();
    if (d.codeLockedUntil && Date.parse(d.codeLockedUntil) > t) {
      throw new ServiceError('Too many wrong codes. Removal for this phone is blocked for an hour.');
    }
    if (!(await checkSecret(String(code ?? ''), d.removalCodeHash))) {
      d.wrongCodes = (d.wrongCodes ?? 0) + 1;
      await audit(who, 'wrong-removal-code', { deviceId: d.id, code: d.payCode, reason: r });
      if (d.wrongCodes >= MAX_WRONG_CODES) {
        d.wrongCodes = 0;
        d.codeLockedUntil = new Date(t + WRONG_CODE_LOCK_MS).toISOString();
        await store.putDevice(d);
        await addAlert('removal_locked', d, `${MAX_WRONG_CODES} wrong removal codes (last try by ${who.name})`);
        throw new ServiceError('Wrong code again. Removal for this phone is now blocked for an hour, and the owner was alerted.');
      }
      await store.putDevice(d);
      throw new ServiceError(`That removal code is wrong. Nothing was changed. (${MAX_WRONG_CODES - d.wrongCodes} tries left)`);
    }
    d.wrongCodes = 0;
    delete d.codeLockedUntil;
    await setStatus(d, 'removed', who.name, r);
    await audit(who, 'remove', { deviceId: d.id, code: d.payCode, reason: r });
    return customerView(d);
  }

  async function removeOverride(who, deviceId, reason) {
    ownerOnly(who);
    const r = needReason(reason);
    const d = await device(deviceId);
    if (d.status === 'removed') throw new ServiceError('The filter is already removed from this phone.');
    await setStatus(d, 'removed', who.name, r);
    await audit(who, 'remove-without-code', { deviceId: d.id, code: d.payCode, reason: r });
    return customerView(d);
  }

  async function setPaid(who, deviceId, paid, reason) {
    ownerOnly(who);
    const r = needReason(reason);
    const d = await device(deviceId);
    const to = paid ? 'paid' : 'unpaid';
    if (d.status === 'removed') throw new ServiceError('The filter is removed from this phone.');
    if (d.status === to) throw new ServiceError(`This phone is already ${to}.`);
    await setStatus(d, to, who.name, r);
    await audit(who, paid ? 'mark-paid' : 'lock-again', { deviceId: d.id, code: d.payCode, reason: r });
    return customerView(d);
  }

  /** Emails the customer a link to choose a new removal code. The link works once, for 24 hours. */
  async function sendCodeReset(who, deviceId) {
    const d = await device(deviceId);
    if (!d.customer?.email) throw new ServiceError('This phone has no customer email yet.');
    const token = randomToken();
    await store.putReset({ tokenHash: await sha256Hex(token), deviceId: d.id, by: who.name, expires: now().getTime() + RESET_HOURS * 3600 * 1000 });
    const link = `${baseUrl}/reset/${token}`;
    await mailer.send({
      to: d.customer.email,
      subject: 'Choose a new Apps Only removal code',
      text: `Hello ${d.customer.name},\n\nSomeone at Apps Only support started a reset of the removal code for your phone (${d.payCode}).\n` +
        `To choose a new code, open this link within ${RESET_HOURS} hours:\n\n${link}\n\nIf you didn't ask for this, ignore this email; your code stays the same.\n`,
    });
    await audit(who, 'send-code-reset', { deviceId: d.id, code: d.payCode });
    await addAlert('code_reset', d, `Reset email sent by ${who.name}`);
  }

  async function resetLookup(token) {
    if (!/^[A-Za-z0-9_-]{20,100}$/.test(token)) return null;
    const r = await store.getReset(await sha256Hex(token));
    if (!r || r.expires < now().getTime()) return null;
    return r;
  }

  function resetForm(token, error = '') {
    return html(`<h1>New removal code</h1>${error ? `<p style="color:#b3261e">${escapeHtml(error)}</p>` : ''}` +
      `<form method="post" action="/reset/${escapeHtml(token)}">` +
      `<p><label>New removal code<br><input name="code" type="password" required minlength="6" maxlength="64" autocomplete="new-password"></label></p>` +
      `<p><label>Type it again<br><input name="code2" type="password" required minlength="6" maxlength="64" autocomplete="new-password"></label></p>` +
      `<p><button>Save the new code</button></p></form>` +
      `<p>Support will ask for this code before removing the filter. Keep it somewhere safe.</p>`, error ? 400 : 200);
  }

  async function resetPage(token) {
    if (!(await resetLookup(token))) return html('<h1>This link has expired</h1><p>Ask support to send a new one.</p>', 404);
    return resetForm(token);
  }

  async function resetSubmit(token, form) {
    const r = await resetLookup(token);
    if (!r) return html('<h1>This link has expired</h1><p>Ask support to send a new one.</p>', 404);
    const code = String(form.code ?? '');
    if (code.length < 6 || code.length > 64 || /\s/.test(code)) return resetForm(token, 'The code needs 6 to 64 characters, with no spaces.');
    if (code !== form.code2) return resetForm(token, "The two codes don't match.");
    const d = await device(r.deviceId);
    d.removalCodeHash = await hashSecret(code);
    d.wrongCodes = 0;
    delete d.codeLockedUntil;
    await store.putDevice(d);
    await store.deleteReset(r.tokenHash);
    await store.addAudit({ at: iso(), by: 'customer', role: 'customer', action: 'new-removal-code', deviceId: d.id, code: d.payCode });
    return html('<h1>Saved</h1><p>Your new removal code is set. You can close this page.</p>');
  }

  return {
    customerView, uninstallCodeFor, searchCustomers, addAlert, listAlerts, handleAlert, checkUnpaid,
    removeWithCode, removeOverride, setPaid, sendCodeReset, resetPage, resetSubmit,
  };
}

/** Collects emails instead of sending them (tests and local runs, until an email service is picked). */
export class OutboxMailer {
  constructor() {
    this.sent = [];
  }
  async send(msg) {
    this.sent.push(msg);
    console.log(`[email queued] ${msg.subject}`);
  }
}
