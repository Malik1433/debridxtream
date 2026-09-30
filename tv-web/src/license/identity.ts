import type { Platform } from '../platform'
import { sha256Bytes, sha256Hex } from '../sha256'

/**
 * The TV's licence id, activation code and identity proof - the same three things Android derives
 * (LicenseManager.installId / deriveActivationCode, DeviceIdentity.proofFor), from the TV's own id.
 * The Firestore rules and functions are platform-free (design §6), so these are all they need.
 */

/** Android: "hw-" + sha256("debridxtream-device-v1:" + ANDROID_ID)[:32]. TVs: "tv-", and the
 * platform inside the hash, so a Samsung, an LG and an Android device can never share an id. */
export function installIdFor(platform: Platform, tvId: string): string {
  return 'tv-' + sha256Hex(`debridxtream-device-v1:${platform}:${tvId}`).slice(0, 32)
}

/** Port of LicenseManager.deriveActivationCode (and functions/index.js): XXXX-XXXX, no 0/O/1/I. */
export function deriveActivationCode(installId: string): string {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  const digest = sha256Bytes(installId)
  let code = ''
  for (let i = 0; i < 8; i++) code += alphabet[digest[i] % alphabet.length]
  return `${code.slice(0, 4)}-${code.slice(4, 8)}`
}

/** Port of DeviceIdentity.proofFor: only this TV can produce it; the installId does not give it away. */
export function identityProofFor(platform: Platform, tvId: string): string {
  return sha256Hex(`debridxtream-identity-proof-v1:${platform}:${tvId}`)
}

/**
 * The TV id to hash. Samsung: getDuid() - the one the APP reads, never the certificate's (design §6).
 * No hardware id (a PC browser during development): a random id kept in localStorage, which is also
 * what Android does without ANDROID_ID.
 */
export function tvIdOrInstallSecret(hardwareId: string | null, storage: Pick<Storage, 'getItem' | 'setItem'>): string {
  if (hardwareId) return hardwareId
  const KEY = 'dx.installSecret'
  let secret = storage.getItem(KEY)
  if (!secret) {
    secret = `rnd-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`
    storage.setItem(KEY, secret)
  }
  return secret
}
