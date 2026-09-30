/**
 * Port of LicenseManager.cachedState / isTrialActive / trialDaysLeft and OnlineCheckFreshness -
 * the licence gate's decisions, pure. Same numbers as Android: 7-day trial from the server's
 * createdAt, 3 days' grace once verified, 48 h for a TV that has never reached the server.
 */
export const DAY_MS = 24 * 60 * 60 * 1000
export const TRIAL_MS = 7 * DAY_MS
export const VERIFIED_GRACE_MS = 3 * DAY_MS
export const FIRST_RUN_GRACE_MS = 48 * 60 * 60 * 1000

export type Status = 'pending' | 'active' | 'inactive'

/** What the TV remembers about its licence between launches (Android: LicensePreferences). */
export interface LicenseCache {
  enforce: boolean
  status: Status
  tier: string
  expiresAt: number
  createdAt: number
  /** Last REAL server round-trip - never a cached snapshot, or the gate is decorative. */
  lastVerifiedAt: number
  firstSeenAt: number
  everEntitled: boolean
  docCreated: boolean
}

export const EMPTY_CACHE: LicenseCache = {
  enforce: false, status: 'pending', tier: 'normal', expiresAt: 0, createdAt: 0,
  lastVerifiedAt: 0, firstSeenAt: 0, everEntitled: false, docCreated: false,
}

export type LockReason = 'pending' | 'deactivated' | 'expired' | 'trial_ended' | 'offline_too_long'
export type LicenseState =
  | { kind: 'active'; tier: string; trialDaysLeft: number | null }
  | { kind: 'locked'; reason: LockReason }

export function isCurrentlyEntitled(c: LicenseCache, now: number): boolean {
  return c.status === 'active' && (c.expiresAt <= 0 || c.expiresAt > now)
}

export function isOnlineCheckFresh(c: LicenseCache, now: number): boolean {
  if (c.lastVerifiedAt > 0) {
    if (c.lastVerifiedAt >= now) return true // a clock rolled back must not read as "future"
    return now - c.lastVerifiedAt <= VERIFIED_GRACE_MS
  }
  if (c.firstSeenAt <= 0 || c.firstSeenAt >= now) return true
  return now - c.firstSeenAt <= FIRST_RUN_GRACE_MS
}

/** A device that was ever activated gets no fresh trial after an admin removes it. */
export function isTrialActive(c: LicenseCache, now: number): boolean {
  if (c.status !== 'pending' || c.everEntitled) return false
  return c.createdAt > 0 && now < c.createdAt + TRIAL_MS
}

export function trialDaysLeft(c: LicenseCache, now: number): number {
  if (!isTrialActive(c, now)) return 0
  return Math.max(1, Math.ceil((c.createdAt + TRIAL_MS - now) / DAY_MS))
}

/** Fail-open while the owner's global `enforce` switch is off - exactly as on Android. */
export function licenseState(c: LicenseCache, now: number): LicenseState {
  if (!c.enforce) return { kind: 'active', tier: c.tier, trialDaysLeft: null }
  if (!isOnlineCheckFresh(c, now)) return { kind: 'locked', reason: 'offline_too_long' }
  if (isCurrentlyEntitled(c, now)) return { kind: 'active', tier: c.tier, trialDaysLeft: null }
  if (isTrialActive(c, now)) return { kind: 'active', tier: 'premium', trialDaysLeft: trialDaysLeft(c, now) }
  if (c.status === 'active') return { kind: 'locked', reason: 'expired' }
  if (c.status === 'inactive') return { kind: 'locked', reason: 'deactivated' }
  if (c.status === 'pending' && c.createdAt > 0) return { kind: 'locked', reason: 'trial_ended' }
  return { kind: 'locked', reason: 'pending' }
}

/** A licence doc as the TV reads it; createdAt/expiresAt may be a Firestore Timestamp or millis. */
export interface LicenseDoc {
  status?: unknown
  tier?: unknown
  expiresAt?: unknown
  createdAt?: unknown
}

function millis(raw: unknown): number | null {
  if (typeof raw === 'number') return raw
  if (raw && typeof (raw as { toMillis?: () => number }).toMillis === 'function') return (raw as { toMillis: () => number }).toMillis()
  return null
}

/** Port of syncCacheFromSnapshot. A pending serverTimestamp (null in the local echo) keeps the old createdAt. */
export function applyDoc(c: LicenseCache, d: LicenseDoc, now: number): LicenseCache {
  const next: LicenseCache = { ...c, docCreated: true }
  if (d.status === 'pending' || d.status === 'active' || d.status === 'inactive') next.status = d.status
  next.tier = typeof d.tier === 'string' ? d.tier : 'normal'
  next.expiresAt = millis(d.expiresAt) ?? 0
  next.createdAt = millis(d.createdAt) ?? c.createdAt
  if (isCurrentlyEntitled(next, now)) next.everEntitled = true
  return next
}

/** The admin DELETED this licence: under enforcement a revocation (Android: onLicenseDocDeleted). */
export function applyDeleted(c: LicenseCache): LicenseCache {
  return c.enforce && c.status !== 'inactive' ? { ...c, status: 'inactive' } : c
}
