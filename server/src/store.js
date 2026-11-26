// Storage behind a tiny interface, so it can be swapped for Cloudflare D1/KV or Firestore.
//   getDevice(id)  putDevice(device)  findDevice(field, value)  listDevices()  addLog(entry)  listLogs()
//   getUser(name)  putUser(user)  listUsers()
//   getSession(idHash)  putSession(session)  deleteSession(idHash)
//   addAudit(entry)  listAudit()
import { readFileSync, writeFileSync, existsSync, renameSync } from 'node:fs';

export class MemoryStore {
  constructor(data = {}) {
    this.data = { devices: {}, logs: [], users: {}, sessions: {}, audit: [], ...data };
  }
  async getDevice(id) {
    return this.data.devices[id] ?? null;
  }
  async putDevice(device) {
    this.data.devices[device.id] = device;
    this.changed();
  }
  async findDevice(field, value) {
    return Object.values(this.data.devices).find((d) => d[field] === value) ?? null;
  }
  async listDevices() {
    return Object.values(this.data.devices);
  }
  async addLog(entry) {
    this.data.logs.push(entry);
    if (this.data.logs.length > 2000) this.data.logs.splice(0, this.data.logs.length - 2000);
    this.changed();
  }
  async listLogs() {
    return this.data.logs;
  }
  async getUser(name) {
    return this.data.users[name] ?? null;
  }
  async putUser(user) {
    this.data.users[user.name] = user;
    this.changed();
  }
  async listUsers() {
    return Object.values(this.data.users);
  }
  async getSession(idHash) {
    return this.data.sessions[idHash] ?? null;
  }
  async putSession(session) {
    // Drop expired sessions while we're here.
    const t = Date.now();
    for (const [k, v] of Object.entries(this.data.sessions)) if (v.expires < t) delete this.data.sessions[k];
    this.data.sessions[session.idHash] = session;
    this.changed();
  }
  async deleteSession(idHash) {
    delete this.data.sessions[idHash];
    this.changed();
  }
  async addAudit(entry) {
    this.data.audit.push(entry);
    if (this.data.audit.length > 10000) this.data.audit.splice(0, this.data.audit.length - 10000);
    this.changed();
  }
  async listAudit() {
    return this.data.audit;
  }
  changed() {}
}

/** Same as MemoryStore, saved to a JSON file after every change (good enough for one server). */
export class FileStore extends MemoryStore {
  constructor(path) {
    super(existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : {});
    this.path = path;
  }
  changed() {
    writeFileSync(this.path + '.tmp', JSON.stringify(this.data));
    renameSync(this.path + '.tmp', this.path);
  }
}
