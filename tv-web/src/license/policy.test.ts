import { describe, expect, it } from 'vitest'
import { DAY_MS, EMPTY_CACHE, applyDeleted, applyDoc, licenseState, type LicenseCache } from './policy'

const NOW = 1_800_000_000_000
const c = (over: Partial<LicenseCache>): LicenseCache => ({ ...EMPTY_CACHE, lastVerifiedAt: NOW - 1000, ...over })

describe('licenseState (port of LicenseManager.cachedState)', () => {
  it('is open for everyone while enforcement is off', () => {
    expect(licenseState(c({ enforce: false, status: 'inactive' }), NOW).kind).toBe('active')
  })
  it('gives a fresh pending TV its 7-day premium trial, counted in whole days', () => {
    const s = licenseState(c({ enforce: true, status: 'pending', createdAt: NOW - 2 * DAY_MS }), NOW)
    expect(s).toEqual({ kind: 'active', tier: 'premium', trialDaysLeft: 5 })
  })
  it('names why a TV is locked', () => {
    expect(licenseState(c({ enforce: true, status: 'pending', createdAt: NOW - 8 * DAY_MS }), NOW)).toEqual({ kind: 'locked', reason: 'trial_ended' })
    expect(licenseState(c({ enforce: true, status: 'pending' }), NOW)).toEqual({ kind: 'locked', reason: 'pending' })
    expect(licenseState(c({ enforce: true, status: 'active', expiresAt: NOW - 1 }), NOW)).toEqual({ kind: 'locked', reason: 'expired' })
    expect(licenseState(c({ enforce: true, status: 'inactive' }), NOW)).toEqual({ kind: 'locked', reason: 'deactivated' })
  })
  it('gives no second trial to a TV that was ever activated', () => {
    expect(licenseState(c({ enforce: true, status: 'pending', createdAt: NOW - DAY_MS, everEntitled: true }), NOW).kind).toBe('locked')
  })
  it('locks an entitled TV that has not reached the server for 3 days, and forgives a rolled-back clock', () => {
    expect(licenseState(c({ enforce: true, status: 'active', lastVerifiedAt: NOW - 4 * DAY_MS }), NOW))
      .toEqual({ kind: 'locked', reason: 'offline_too_long' })
    expect(licenseState(c({ enforce: true, status: 'active', lastVerifiedAt: NOW + DAY_MS }), NOW).kind).toBe('active')
  })
  it('lets a never-synced TV run 48 h from first sight, not forever', () => {
    const fresh = c({ enforce: true, status: 'pending', lastVerifiedAt: 0, firstSeenAt: NOW - DAY_MS })
    expect(licenseState(fresh, NOW)).toEqual({ kind: 'locked', reason: 'pending' })
    const stale = c({ enforce: true, status: 'active', lastVerifiedAt: 0, firstSeenAt: NOW - 3 * DAY_MS })
    expect(licenseState(stale, NOW)).toEqual({ kind: 'locked', reason: 'offline_too_long' })
  })
})

describe('applyDoc / applyDeleted', () => {
  it('reads Timestamps or millis, keeps createdAt while the server stamp is pending, remembers activation', () => {
    const ts = { toMillis: () => NOW - DAY_MS }
    let x = applyDoc(EMPTY_CACHE, { status: 'active', tier: 'premium', createdAt: ts, expiresAt: NOW + DAY_MS }, NOW)
    expect(x).toMatchObject({ status: 'active', tier: 'premium', createdAt: NOW - DAY_MS, everEntitled: true, docCreated: true })
    x = applyDoc(x, { status: 'active', createdAt: null }, NOW)
    expect(x.createdAt).toBe(NOW - DAY_MS)
  })
  it('turns an admin delete into a revocation only under enforcement', () => {
    expect(applyDeleted({ ...EMPTY_CACHE, enforce: true, status: 'active' }).status).toBe('inactive')
    expect(applyDeleted({ ...EMPTY_CACHE, enforce: false, status: 'active' }).status).toBe('active')
  })
})
