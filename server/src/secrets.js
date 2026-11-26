// Scrambling (hashing) for secrets we must check but never read back: removal codes now,
// portal passwords later. PBKDF2-SHA256 through Web Crypto, so it runs anywhere.
import { b64url, b64urlToBytes, timingSafeEqual } from './util.js';

const enc = new TextEncoder();
const ITERATIONS = 100_000;

async function pbkdf2(secret, salt, iterations) {
  const key = await crypto.subtle.importKey('raw', enc.encode(secret), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, key, 256);
  return b64url(bits);
}

/** Returns "pbkdf2$<iterations>$<salt>$<hash>". */
export async function hashSecret(secret) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  return `pbkdf2$${ITERATIONS}$${b64url(salt)}$${await pbkdf2(secret, salt, ITERATIONS)}`;
}

export async function checkSecret(secret, stored) {
  const [kind, it, salt, hash] = String(stored ?? '').split('$');
  if (kind !== 'pbkdf2' || !hash) return false;
  return timingSafeEqual(await pbkdf2(String(secret), b64urlToBytes(salt), Number(it)), hash);
}
