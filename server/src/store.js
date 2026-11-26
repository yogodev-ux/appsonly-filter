// Storage behind a tiny interface, so it can be swapped for Cloudflare D1/KV or Firestore.
//   getDevice(id)  putDevice(device)  findDevice(field, value)  listDevices()  addLog(entry)  listLogs()
import { readFileSync, writeFileSync, existsSync, renameSync } from 'node:fs';

export class MemoryStore {
  constructor(data = { devices: {}, logs: [] }) {
    this.data = data;
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
  changed() {}
}

/** Same as MemoryStore, saved to a JSON file after every change (good enough for one server). */
export class FileStore extends MemoryStore {
  constructor(path) {
    super(existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : { devices: {}, logs: [] });
    this.path = path;
  }
  changed() {
    writeFileSync(this.path + '.tmp', JSON.stringify(this.data));
    renameSync(this.path + '.tmp', this.path);
  }
}
