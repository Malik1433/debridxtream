import { describe, expect, it } from 'vitest'
import { CONNECT_TIMEOUT_MS, LiveEngine, TICK_MS, type Clock, type LiveMedia, type LiveStatus } from './liveEngine'

/** A clock we advance by hand, with real timers semantics (every/after). */
class FakeClock implements Clock {
  t = 0
  private timers: Array<{ at: number; every: number | null; fn: () => void; dead: boolean }> = []
  now = () => this.t
  every = (ms: number, fn: () => void) => this.add(ms, ms, fn)
  after = (ms: number, fn: () => void) => this.add(ms, null, fn)
  private add(ms: number, every: number | null, fn: () => void) {
    const t = { at: this.t + ms, every, fn, dead: false }
    this.timers.push(t)
    return () => { t.dead = true }
  }
  advance(ms: number): void {
    const end = this.t + ms
    for (;;) {
      const next = this.timers.filter((x) => !x.dead).sort((a, b) => a.at - b.at)[0]
      if (!next || next.at > end) break
      this.t = next.at
      if (next.every) next.at += next.every; else next.dead = true
      next.fn()
    }
    this.t = end
  }
}

/** A player whose position advances only while "flowing" and playing. */
class FakeMedia implements LiveMedia {
  nudge?: () => boolean
  reportsBuffer?: () => boolean
  tryAlternative?: () => boolean
  prefersAlternative?: () => boolean
  pos = 0; ahead = 0; flowing = false; playing = false; speed = 1
  loads: string[] = []; stops = 0
  private err: (e: { http?: number; network: boolean; message: string }) => void = () => undefined
  load(url: string) { this.loads.push(url); this.pos = 0; this.ahead = 0 }
  play() { this.playing = true }
  pause() { this.playing = false }
  stop() { this.stops++; this.playing = false }
  positionMs() { return this.pos }
  bufferedAheadMs() { return this.ahead }
  setSpeed(r: number) { this.speed = r; return true }
  onError(cb: typeof this.err) { this.err = cb }
  fail(e: { http?: number; network: boolean; message: string }) { this.err(e) }
  /** Called by the test every tick: advance position if data flows. */
  step(ms: number) { if (this.flowing && this.playing) { this.pos += ms } }
}

function rig() {
  const clock = new FakeClock()
  const media = new FakeMedia()
  const logs: string[] = []
  const engine = new LiveEngine(media, clock, (l) => logs.push(l))
  const seen: LiveStatus['kind'][] = []
  engine.onStatus((s) => { if (seen[seen.length - 1] !== s.kind) seen.push(s.kind) })
  const run = (ms: number) => { for (let t = 0; t < ms; t += TICK_MS) { media.step(TICK_MS); clock.advance(TICK_MS) } }
  return { clock, media, engine, seen, logs, run }
}
const ch = (id: string) => ({ id, name: `Ch ${id}`, url: `http://x/${id}.ts` })

describe('LiveEngine', () => {
  it('connects, says so when slow, then plays', () => {
    const r = rig()
    r.engine.play(ch('1')); r.run(7000)
    expect(r.engine.current()).toMatchObject({ kind: 'connecting', slow: true })
    r.media.flowing = true; r.media.ahead = 6000; r.run(1000)
    expect(r.engine.current().kind).toBe('playing')
  })

  /**
   * W3 QA 2026-09-30: some 4K channels never produced a picture and never raised an error either,
   * so the pill said "Connecting…" for ever and the viewer had nothing to act on.
   */
  it('gives up on a channel that never shows a picture, and stops the stream', () => {
    const r = rig()
    r.engine.play(ch('1'))
    r.run(CONNECT_TIMEOUT_MS - TICK_MS)
    expect(r.engine.current().kind).toBe('connecting')  // still trying, and still says so
    r.run(TICK_MS * 2)
    expect(r.engine.current()).toMatchObject({ kind: 'failed', message: 'no picture in 25 s' })
    expect(r.media.stops).toBe(1)                        // the connection is not left open
    r.run(10_000)
    expect(r.engine.current().kind).toBe('failed')       // and it stays given up, not a retry loop
  })

  it('a channel that is merely slow to start is not given up on', () => {
    const r = rig()
    r.engine.play(ch('1'))
    r.run(CONNECT_TIMEOUT_MS - 2_000)
    r.media.flowing = true; r.media.ahead = 6000; r.run(1000)
    expect(r.engine.current().kind).toBe('playing')
    r.run(10_000)
    expect(r.engine.current().kind).toBe('playing')
  })

  it('a still position is a stall: holds, then resumes; the 2nd stall waits for 5 s of buffer', () => {
    const r = rig()
    r.engine.play(ch('1')); r.media.flowing = true; r.media.ahead = 6000; r.run(3000)
    r.media.flowing = false; r.media.ahead = 0; r.run(2500)
    expect(r.engine.current().kind).toBe('buffering')
    r.media.ahead = 2500; r.media.flowing = true; r.run(1500)
    expect(r.engine.current().kind).toBe('playing') // stall 1: usual threshold
    r.media.flowing = false; r.media.ahead = 0; r.run(2500)
    r.media.ahead = 3000; r.run(1500)
    expect(r.engine.current()).toMatchObject({ kind: 'buffering', waitingForMs: 5000 })
    r.media.ahead = 5200; r.media.flowing = true; r.run(1500)
    expect(r.engine.current().kind).toBe('playing')
    expect(r.logs.filter((l) => l.startsWith('live cushion: stall'))).toEqual([
      'live cushion: stall 1 in 3 min, usual threshold', 'live cushion: stall 2 in 3 min, waiting for 5s of buffer'])
    expect(r.engine.meter.count).toBe(2)
  })

  it('plays at 0.97x while the cushion is thin, 1x once full', () => {
    const r = rig()
    r.engine.play(ch('1')); r.media.flowing = true; r.media.ahead = 3000; r.run(4000)
    expect(r.media.speed).toBe(0.97)
    r.media.ahead = 9000; r.run(4000)
    expect(r.media.speed).toBe(1)
  })

  it('an error retries twice quickly, then holds on; a dead channel gives up at once', () => {
    const r = rig()
    r.engine.play(ch('1')); r.media.flowing = true; r.media.ahead = 6000; r.run(2000)
    r.media.flowing = false
    r.media.fail({ network: true, message: 'net' })
    expect(r.engine.current()).toMatchObject({ kind: 'reconnecting', inMs: 1000 })
    r.run(1000); r.media.fail({ network: true, message: 'net' })
    expect(r.engine.current()).toMatchObject({ kind: 'reconnecting', inMs: 2000 })
    r.run(2000); r.media.fail({ network: true, message: 'net' })
    expect(r.engine.current()).toMatchObject({ kind: 'reconnecting', inMs: 5000 })
    expect(r.media.loads).toHaveLength(3)
    r.media.fail({ http: 404, network: true, message: 'gone' })
    expect(r.engine.current()).toMatchObject({ kind: 'failed', message: 'HTTP 404' })
  })

  it('a zap is not an interruption and resets the stall count', () => {
    const r = rig()
    r.engine.play(ch('1')); r.media.flowing = true; r.media.ahead = 6000; r.run(2000)
    r.engine.play(ch('2')); r.run(2000)
    expect(r.engine.meter.count).toBe(0)
    expect(r.media.loads).toEqual(['http://x/1.ts', 'http://x/2.ts'])
  })

  it('a hold that gets no data for 15 s reconnects instead of waiting forever', () => {
    const r = rig()
    r.engine.play(ch('1')); r.media.flowing = true; r.media.ahead = 6000; r.run(2000)
    r.media.flowing = false; r.media.ahead = 0; r.run(18_000)
    expect(r.seen).toContain('reconnecting')
    expect(r.media.loads).toHaveLength(2)
  })

  it('on a player that buffers by itself: no hold, no slow-fill; a dead picture still reconnects', () => {
    const r = rig()
    let own = false
    r.media.reportsBuffer = () => !own
    own = true
    r.engine.play(ch('1'))
    r.media.flowing = true; r.media.ahead = 0; r.run(2000)
    expect(r.engine.current().kind).toBe('playing')
    expect(r.media.speed).toBe(1) // no 0.97x on a buffer we cannot see
    r.media.flowing = false; r.run(3000)
    expect(r.engine.current().kind).toBe('buffering')
    expect(r.media.playing).toBe(true) // never paused: the player is filling its own buffer
    r.media.flowing = true; r.run(1000)
    expect(r.engine.current().kind).toBe('playing')
    expect(r.engine.meter.count).toBe(1)
    r.media.flowing = false; r.run(16_000)
    expect(r.logs.some((l) => l.includes('no picture while buffering'))).toBe(true)
    expect(r.seen.slice(-2)).toEqual(['reconnecting', 'connecting'])
  })

  it('a channel that cannot start tries the other player once before saying it is not available', () => {
    const r = rig()
    let left = 1
    r.media.tryAlternative = () => left-- > 0
    r.engine.play(ch('1'))
    r.run(CONNECT_TIMEOUT_MS + 1000)
    expect(r.engine.current()).toMatchObject({ kind: 'connecting', slow: false })
    r.run(CONNECT_TIMEOUT_MS + 1000)
    expect(r.engine.current().kind).toBe('failed')
  })

  it('moves the channel now playing to the other player by hand (QA A/B)', () => {
    const r = rig()
    let left = 1
    r.media.tryAlternative = () => left-- > 0
    expect(r.engine.useAlternativePlayer()).toBe(false) // nothing playing
    r.engine.play(ch('1'))
    r.media.flowing = true; r.run(1000)
    expect(r.engine.current().kind).toBe('playing')
    expect(r.engine.useAlternativePlayer()).toBe(true)
    expect(r.engine.current().kind).toBe('connecting')
    r.run(1000)
    expect(r.engine.current().kind).toBe('playing')
    expect(r.engine.useAlternativePlayer()).toBe(false)
  })

  it('moves a non-AAC channel with no picture to the other player at 6 s, not 25 s', () => {
    const r = rig()
    let switched = false
    r.media.prefersAlternative = () => !switched
    r.media.tryAlternative = () => { if (switched) return false; switched = true; return true }
    r.engine.play(ch('1'))
    r.run(5_000)
    expect(switched).toBe(false)
    r.run(1_500)
    expect(switched).toBe(true)
    expect(r.logs.some((l) => /no picture in 6\d\d\d ms/.test(l))).toBe(true)
    expect(r.engine.current()).toMatchObject({ kind: 'connecting' })
  })

  it('W4 QA R7: a frozen picture with data in hand counts, is nudged, and is reopened if it stays frozen', () => {
    const r = rig()
    let nudges = 0
    r.media.nudge = () => { nudges++; return true }
    r.engine.play(ch('1')); r.media.flowing = true; r.media.ahead = 45_000; r.run(2000)
    expect(r.engine.current().kind).toBe('playing')
    r.engine.pictureFrozen()
    expect(r.engine.current().kind).toBe('buffering')
    expect(r.engine.meter.count).toBe(1)
    expect(nudges).toBe(1)
    r.run(1000) // the clock runs on, but that is not "playing" while frozen
    expect(r.engine.current().kind).toBe('buffering')
    r.engine.pictureThawed()
    expect(r.engine.current().kind).toBe('playing')
    expect(r.media.loads.length).toBe(1)
    r.engine.pictureFrozen(); r.run(3500)
    expect(r.media.loads.length).toBe(2)
    expect(r.logs.some((l) => l.includes('still frozen'))).toBe(true)
  })

  it('W4 QA R7b: a reconnect is timed from its own load, and called "picture back"', () => {
    const r = rig()
    r.engine.play(ch('1')); r.media.flowing = true; r.run(1000)
    r.run(60_000)
    r.media.fail({ network: true, message: 'drop' })
    r.run(10_000)
    const lines = r.logs.filter((l) => /picture (on|back)|first picture/.test(l))
    expect(lines[0]).toMatch(/first picture on Ch 1 after \d+ ms/)
    const back = lines.find((l) => l.includes('picture back'))
    expect(back).toBeDefined()
    expect(Number(/after (\d+) ms/.exec(back!)![1])).toBeLessThan(10_000)
  })
})
