import { describe, expect, it } from 'vitest'
import { FROZEN_AFTER_MS, PictureWatch } from './pictureWatch'

describe('PictureWatch', () => {
  it('reports a freeze when the clock runs and no frame is drawn, then the thaw', () => {
    const w = new PictureWatch()
    let t = 0, pos = 0, frames = 0
    const step = (draw: boolean) => { t += 500; pos += 500; if (draw) frames += 12; return w.sample(t, pos, frames) }
    for (let i = 0; i < 4; i++) expect(step(true)).toBeNull()
    const events = Array.from({ length: 8 }, () => step(false)).filter(Boolean)
    expect(events).toEqual([{ kind: 'frozen', positionMovedMs: FROZEN_AFTER_MS }])
    expect(step(true)).toEqual({ kind: 'thawed', frozenForMs: 4_000 })
  })
  it('stays quiet in a plain stall (nothing moves) and when frames are unknown', () => {
    const w = new PictureWatch()
    w.sample(0, 1000, 10)
    for (let t = 500; t < 5000; t += 500) expect(w.sample(t, 1000, 10)).toBeNull()
    expect(w.sample(6000, 9000, null)).toBeNull()
  })
})
