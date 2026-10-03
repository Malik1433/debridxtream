import { describe, expect, it } from 'vitest'
import { FrameStats, WINDOW } from './frameStats'

describe('FrameStats', () => {
  it('reads 60 fps smooth, and counts janky frames', () => {
    const s = new FrameStats()
    for (let i = 0; i < 90; i++) s.push(16.7)
    for (let i = 0; i < 10; i++) s.push(100)
    const r = s.snapshot()
    expect(r.jankPct).toBe(10)
    expect(r.worst).toBe(100)
    expect(r.p95).toBe(100)
    expect(r.fps).toBeGreaterThan(30)
  })
  it('keeps a bounded window and ignores pauses', () => {
    const s = new FrameStats()
    for (let i = 0; i < WINDOW + 50; i++) s.push(16)
    s.push(60_000); s.push(-1)
    expect(s.snapshot().n).toBe(WINDOW)
    expect(s.snapshot().fps).toBe(63)
  })
})
