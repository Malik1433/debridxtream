import { beforeEach, describe, expect, it } from 'vitest'
import { KEPT_MAX, keptLines, liveLog } from './liveLog'

class Store { m = new Map<string, string>(); getItem(k: string) { return this.m.get(k) ?? null } setItem(k: string, v: string) { this.m.set(k, v) } removeItem(k: string) { this.m.delete(k) } }
describe('liveLog kept lines', () => {
  beforeEach(() => { (globalThis as { localStorage?: unknown }).localStorage = new Store() })
  it('keeps errors, player and picture lines - never an address - and not the chatter', () => {
    liveLog('live error: NetworkError/Exception at http://host:80/live/u/p/1.ts - retry in 1000 ms')
    liveLog('player: AVPlay')
    liveLog('picture: FROZEN - no frame drawn for 2 s while the position moved 2.4 s')
    liveLog('live cushion: speed 0.97')
    const k = keptLines()
    expect(k.length).toBe(3)
    expect(k[0]).toMatch(/live error: NetworkError\/Exception at <url> - retry in 1000 ms$/)
    expect(k.join(' ')).not.toContain('host')
  })
  it('is bounded', () => {
    for (let i = 0; i < KEPT_MAX + 10; i++) liveLog(`player: p${i}`)
    expect(keptLines().length).toBe(KEPT_MAX)
  })
})
