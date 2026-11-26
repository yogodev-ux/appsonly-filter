// Prints a new license signing key pair.
//   LICENSE_PRIVATE_JWK  -> server secret (never commit the production one)
//   public key           -> the app's licensePublicKey (gradle property for release builds)
import { generateKeys } from '../src/signer.js';
const { privateJwk, publicSpkiB64 } = await generateKeys();
console.log('LICENSE_PRIVATE_JWK=' + JSON.stringify(privateJwk));
console.log('LICENSE_PUBLIC_KEY=' + publicSpkiB64);
