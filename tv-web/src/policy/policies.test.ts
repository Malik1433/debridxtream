import { describe, expect, it } from 'vitest'
import { InterruptionMeter } from './interruptionMeter'
import { RebufferPatience, SlowFillPolicy, STALL_WINDOW_MS, MAX_WAIT_MS, SLOW_SPEED } from './liveCushion'
import { LiveHoldOn, MAX_HOLD_MS, networkRecentlyLost, NETWORK_GRACE_MS } from './liveHoldOn'

describe('RebufferPatience (port of LiveCushionPolicyTest)', () => {
  let now = 0
  const make = () => { now = 0; return new RebufferPatience(() => now) }
  const stall = (p: RebufferPatience, buffered: number, base = true) => p.shouldResume(buffered, base)

  it('first stall on the usual threshold, second waits 5 s, third 8 s', () => {
    const p = make()
    expect(stall(p, 2000)).toBe(true); now += 30_000
    expect(stall(p, 2000)).toBe(false); expect(stall(p, 5000)).toBe(true); now += 30_000
    expect(stall(p, 6000)).toBe(false); expect(stall(p, 8000)).toBe(true)
  })
  it('forgets stalls older than 3 min, and never waits past 10 s', () => {
    const p = make()
    stall(p, 2000); now += STALL_WINDOW_MS + 1
    expect(stall(p, 2000)).toBe(true)
    now += 10_000
    expect(stall(p, 3000)).toBe(false); now += MAX_WAIT_MS
    expect(stall(p, 3000)).toBe(true)
  })
  it('a reconnect after a rebuffer is the same stall; a viewer re-open is none', () => {
    const p = make()
    p.onStream('a'); stall(p, 2000); now += 40_000
    expect(p.shouldStart(0, true, false)).toBe(false) // outage 2 opens
    p.expectRecoveryStart(); now += 5000
    expect(p.shouldStart(3000, false, true)).toBe(false) // still stall 2: 5 s
    expect(p.shouldStart(5000, false, true)).toBe(true)
    for (let i = 0; i < 3; i++) expect(p.shouldStart(1000, false, true)).toBe(true) // viewer starts
  })
  it('a feed switch keeps the count; a new channel resets it', () => {
    const p = make()
    p.onStream('feed-a'); stall(p, 2000); now += 40_000; stall(p, 5000); now += 40_000
    p.expectFeedSwitch(); p.onStream('feed-b')
    expect(stall(p, 6000)).toBe(false)
    p.onStream('other-channel'); now += 1000
    expect(stall(p, 2000)).toBe(true)
  })
  it('slow-fill: 0.97x under 5 s, back to 1x at 8 s', () => {
    const f = new SlowFillPolicy()
    expect([f.speedFor(6000), f.speedFor(4000), f.speedFor(7000), f.speedFor(8000)]).toEqual([1, SLOW_SPEED, SLOW_SPEED, 1])
  })
})

describe('LiveHoldOn + network grace', () => {
  it('backs off 5/10/20/30 s, gives up after 30 min or on a dead account', () => {
    let now = 0
    const h = new LiveHoldOn(() => now)
    expect([1, 2, 3, 4, 5].map(() => h.nextRetryDelayMs(undefined, false))).toEqual([5000, 10000, 20000, 30000, 30000])
    now = MAX_HOLD_MS + 1
    expect(h.nextRetryDelayMs(undefined, false)).toBeNull()
    h.onRecovered(); now = 0
    expect(h.nextRetryDelayMs(503, false)).toBe(5000)
    expect(h.nextRetryDelayMs(404, false)).toBeNull()
    expect(h.nextRetryDelayMs(undefined, true)).toBeNull()
  })
  it('does not blame the feed while our own line is down or just back', () => {
    expect(networkRecentlyLost(false, 0, 1000)).toBe(true)
    expect(networkRecentlyLost(true, 1000, 30_000)).toBe(true)
    expect(networkRecentlyLost(true, 1000, 1000 + NETWORK_GRACE_MS)).toBe(false)
  })
})

describe('InterruptionMeter (port of PlaybackInterruptionMeterTest)', () => {
  it('counts a stop the viewer did not ask for; one outage across a rebuild is one; zaps/pauses are theirs', () => {
    let now = 0
    const m = new InterruptionMeter(() => now)
    m.onPlaying(); now += 60_000
    m.onStopped(true); now += 5000; m.onStopped(true); now += 15_000; m.onPlaying(); now += 60_000
    m.onStopped(false); m.onPlaying()
    m.onUserChange(); m.onStopped(true); now += 2000; m.onPlaying(); now += 1000
    expect(m.count).toBe(1)
    expect(m.logLine('live')).toContain('count=1 stopped_ms=20000 longest_ms=20000')
  })
})
