// Signs licenses with ECDSA P-256 (supported by Android's Java crypto and by Web Crypto).
// A license is  base64url(JSON payload) + "." + base64url(signature as raw r||s).
import { b64url, b64urlToBytes } from './util.js';

const ALG = { name: 'ECDSA', namedCurve: 'P-256' };
const SIGN = { name: 'ECDSA', hash: 'SHA-256' };

export async function createSigner(privateJwk) {
  const key = await crypto.subtle.importKey('jwk', privateJwk, ALG, false, ['sign']);
  return {
    async sign(payload) {
      const body = b64url(new TextEncoder().encode(JSON.stringify(payload)));
      const sig = await crypto.subtle.sign(SIGN, key, new TextEncoder().encode(body));
      return `${body}.${b64url(sig)}`;
    },
  };
}

/** For tests and tools: checks a license against a public key (SPKI, base64). */
export async function verifyLicense(license, publicSpkiB64) {
  const [body, sig] = license.split('.');
  const spki = Uint8Array.from(atob(publicSpkiB64), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('spki', spki, ALG, false, ['verify']);
  const ok = await crypto.subtle.verify(SIGN, key, b64urlToBytes(sig), new TextEncoder().encode(body));
  return ok ? JSON.parse(new TextDecoder().decode(b64urlToBytes(body))) : null;
}

export async function generateKeys() {
  const pair = await crypto.subtle.generateKey(ALG, true, ['sign', 'verify']);
  const privateJwk = await crypto.subtle.exportKey('jwk', pair.privateKey);
  const spki = new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey));
  return { privateJwk, publicSpkiB64: btoa(String.fromCharCode(...spki)) };
}
