import { describe, expect, it } from 'vitest'
import { VOD_MAX_RETRIES, VodRecovery, backoffMs } from './vodRecovery'

describe('VodRecovery', () => {
  it('reconnects five times, 1/2/4/8/8 s apart, from just before the failure', () => {
    const r = new VodRecovery()
    r.progress(351_000)
    const steps = Array.from({ length: VOD_MAX_RETRIES }, () => r.onFailure('PLAYER_ERROR_CONNECTION_FAILED'))
    expect(steps.map((s) => s?.delayMs)).toEqual([1_000, 2_000, 4_000, 8_000, 8_000])
    expect(steps[0]?.resumeMs).toBe(349_000)
    expect(r.onFailure('PLAYER_ERROR_CONNECTION_FAILED')).toBeNull()
  })
  it('gets the full allowance again once it plays', () => {
    const r = new VodRecovery()
    for (let i = 0; i < VOD_MAX_RETRIES; i++) r.onFailure('video error 2')
    r.recovered()
    expect(r.onFailure('video error 2')?.attempt).toBe(1)
  })
  it('gives up at once when waiting cannot help', () => {
    const r = new VodRecovery()
    expect(r.onFailure('PLAYER_ERROR_NOT_SUPPORTED_FILE')).toBeNull()
    expect(r.onFailure('video error 4')).toBeNull()
    expect(r.onFailure('prepare: PLAYER_ERROR_INVALID_URI')).toBeNull()
  })
  it('starts from 0 when nothing played yet', () => {
    expect(new VodRecovery().onFailure('open: PLAYER_ERROR_CONNECTION_FAILED')?.resumeMs).toBe(0)
    expect(backoffMs(9)).toBe(8_000)
  })
})
