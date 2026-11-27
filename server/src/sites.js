// Websites that phones report an allowed app still needs (step 3 of the app website scan).
//
// The phone reads each allowed app's install files for the websites it uses. If the app still
// asks for a name that is blocked, the phone reports app + website (no personal data). The
// owner sees the reports in the owner portal (/owner/app-sites) and approves or dismisses each.
// Approved websites are sent to every phone, for that app only.
//
//   report(device, sites)     sites: [{app, host}]  -> records new and repeat reports
//   list(status)              'open' | 'approved' | 'dismissed'
//   decide(who, id, action)   'approve' | 'dismiss' | 'undo' (owner only)
//   approved()                {"com.example.app": ["api.example.net", ...]}
import { randomToken } from './util.js';
import { ServiceError } from './services.js';

const KEY = 'siteRequests';
const MAX = 5000;
const APP = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
const HOST = /^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}$/;

export function cleanSite(s) {
  const app = String(s?.app ?? '').trim();
  const host = String(s?.host ?? '').trim().toLowerCase().replace(/\.$/, '');
  if (app.length > 150 || !APP.test(app) || !HOST.test(host)) return null;
  return { app, host };
}

export function createSiteRequests({ store, now = () => new Date() }) {
  const iso = () => now().toISOString();
  const all = async () => (await store.getMeta(KEY)) ?? [];
  const save = (list) => store.putMeta(KEY, list.slice(-MAX));

  async function report(device, sites) {
    const list = await all();
    let added = 0;
    for (const raw of sites.slice(0, 100)) {
      const s = cleanSite(raw);
      if (!s) continue;
      let r = list.find((x) => x.app === s.app && x.host === s.host);
      if (!r) {
        r = { id: 'site_' + randomToken(9), app: s.app, host: s.host, status: 'open', firstAt: iso(), phones: [], count: 0 };
        list.push(r);
        added++;
      }
      r.lastAt = iso();
      if (device && !r.phones.includes(device.id)) {
        r.phones = [...r.phones, device.id].slice(-50);
        r.count += 1;
      }
    }
    await save(list);
    return added;
  }

  async function list(status = 'open') {
    return (await all()).filter((r) => r.status === status)
      .sort((a, b) => b.count - a.count || String(b.lastAt).localeCompare(String(a.lastAt)));
  }

  async function decide(who, id, action) {
    if (who.role !== 'owner') throw new ServiceError('Only the owner can do this.');
    const l = await all();
    const r = l.find((x) => x.id === id);
    if (!r) throw new ServiceError('Website report not found.');
    const status = { approve: 'approved', dismiss: 'dismissed', undo: 'open' }[action];
    if (!status) throw new ServiceError('Unknown action.');
    r.status = status;
    r.decidedAt = iso();
    r.decidedBy = who.name;
    await save(l);
    await store.addAudit({ at: iso(), by: who.name, role: who.role, action: `site-${action}`, detail: `${r.host} for ${r.app}` });
    return r;
  }

  async function approved() {
    const out = {};
    for (const r of await all()) if (r.status === 'approved') (out[r.app] ??= []).push(r.host);
    return out;
  }

  return { report, list, decide, approved };
}
