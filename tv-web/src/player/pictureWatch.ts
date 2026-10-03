/**
 * W4 QA R7: "the sound plays, the picture stops, and it is not called buffering". The stall meter
 * watches the POSITION; a decoder that stops drawing while the clock runs on is invisible to it.
 * This watches the two separately - decoded frames and position - and reports the moment they
 * disagree, so a QA run can say what the position did during a freeze. It measures; it decides
 * nothing (policy changes come after the measurement, per the report).
 */
export const FROZEN_AFTER_MS = 2_000

export type PictureEvent = { kind: 'frozen'; positionMovedMs: number } | { kind: 'thawed'; frozenForMs: number }

export class PictureWatch {
  private lastFrames = -1
  private lastPos = -1
  private stillSince = -1
  private posAtStill = 0
  private frozen = false

  /** One sample: `frames` = decoded video frames so far (null when the player cannot say). */
  sample(now: number, positionMs: number | null, frames: number | null): PictureEvent | null {
    if (frames === null || positionMs === null) { this.reset(); return null }
    if (frames < this.lastFrames) this.reset() // a new stream
    const drew = frames > this.lastFrames
    const moved = this.lastPos >= 0 && positionMs > this.lastPos
    this.lastFrames = frames
    this.lastPos = positionMs
    if (drew) {
      this.stillSince = -1
      if (this.frozen) { this.frozen = false; return { kind: 'thawed', frozenForMs: now - this.frozenAt } }
      return null
    }
    if (!moved) return null // nothing plays: that is a stall, the meter's business
    if (this.stillSince < 0) { this.stillSince = now; this.posAtStill = positionMs; return null }
    if (!this.frozen && now - this.stillSince >= FROZEN_AFTER_MS) {
      this.frozen = true
      this.frozenAt = this.stillSince
      return { kind: 'frozen', positionMovedMs: positionMs - this.posAtStill }
    }
    return null
  }

  private frozenAt = 0
  private reset(): void { this.lastFrames = -1; this.lastPos = -1; this.stillSince = -1; this.frozen = false }
}

/** Decoded frames from a <video>, where the engine exposes them (MSE / mpegts.js). */
export function decodedFrames(v: HTMLVideoElement | null): number | null {
  if (!v) return null
  const q = (v as HTMLVideoElement & { getVideoPlaybackQuality?: () => { totalVideoFrames: number } }).getVideoPlaybackQuality?.()
  if (q && typeof q.totalVideoFrames === 'number') return q.totalVideoFrames
  const w = (v as HTMLVideoElement & { webkitDecodedFrameCount?: number }).webkitDecodedFrameCount
  return typeof w === 'number' ? w : null
}
