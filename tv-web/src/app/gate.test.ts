import { describe, expect, it } from 'vitest'
import type { AppState } from './controller'
import { gateFor } from './gate'

const base: AppState = {
  license: { kind: 'active', tier: 'premium', trialDaysLeft: null },
  registered: true,
  activationCode: 'XXXX-XXXX',
  claimed: true,
  providerName: 'a provider',
  hasAccount: true,
  catalogue: { channels: 15_951, categories: 120, at: 1 },
  sync: { kind: 'done', channels: 15_951, categories: 120, at: 1 },
  library: null,
  librarySyncing: false,
}

describe('gateFor', () => {
  it('asks for a licence, then a phone, then a first catalogue, in that order', () => {
    expect(gateFor({ ...base, license: { kind: 'locked', reason: 'pending' }, hasAccount: false, catalogue: null })
      .screen).toBe('activation')
    expect(gateFor({ ...base, hasAccount: false, catalogue: null }).screen).toBe('setup')
    expect(gateFor({ ...base, catalogue: null, sync: { kind: 'running', provider: 'p', moved: false } }).screen)
      .toBe('syncing')
    expect(gateFor(base).screen).toBe('app')
  })

  /** W2 QA 2026-09-30: this is the one that was broken, in both directions. */
  it('keeps the app on screen while a refresh runs, fails, or has not started', () => {
    const held = { ...base, catalogue: { channels: 15_951, categories: 120, at: 1 } }
    expect(gateFor({ ...held, sync: { kind: 'running', provider: 'p', moved: false } }).screen).toBe('app')
    expect(gateFor({ ...held, sync: { kind: 'error', provider: 'p', message: 'timeout' } }).screen).toBe('app')
    expect(gateFor({ ...held, sync: { kind: 'idle' } }).screen).toBe('app')
  })

  it('still waits when the only catalogue we ever got was empty', () => {
    expect(gateFor({ ...base, catalogue: { channels: 0, categories: 0, at: 1 },
      sync: { kind: 'running', provider: 'p', moved: false } }).screen).toBe('syncing')
  })

  /**
   * A move is not a refresh: the held channels belong to the OLD provider and both providers number
   * their streams from 1, so they are wrong, not stale. The controller drops the catalogue on a
   * move, which puts the gate back here - the only screen that says "You were moved to <name>".
   */
  it('goes back to the sync screen when the provider changed under it', () => {
    expect(gateFor({ ...base, providerName: 'the new one', catalogue: null,
      sync: { kind: 'running', provider: 'the new one', moved: true } }).screen).toBe('syncing')
  })

  it('a locked licence wins over everything, even a full catalogue', () => {
    expect(gateFor({ ...base, license: { kind: 'locked', reason: 'deactivated' } }).screen).toBe('activation')
  })
})
