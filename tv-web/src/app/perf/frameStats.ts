/**
 * Frame timing over the last few seconds, for the on-TV readout (W4 QA R6): a retail Samsung has
 * no inspector, so the TV has to show its own frame rate. Same gate words as the Android
 * perf_check: a frame over 33 ms (two refreshes at 60 Hz) is janky.
 */
export const JANK_MS = 33
export const WINDOW = 180

export class FrameStats {
  private d: number[] = []
  push(deltaMs: number): void {
    if (!(deltaMs > 0) || deltaMs > 5_000) return // a hidden tab or a resume, not a frame
    this.d.push(deltaMs)
    if (this.d.length > WINDOW) this.d.shift()
  }
  snapshot(): { fps: number; p95: number; jankPct: number; worst: number; n: number } {
    const n = this.d.length
    if (!n) return { fps: 0, p95: 0, jankPct: 0, worst: 0, n: 0 }
    const sum = this.d.reduce((a, b) => a + b, 0)
    const sorted = [...this.d].sort((a, b) => a - b)
    return {
      fps: Math.round((1000 * n) / sum),
      p95: Math.round(sorted[Math.min(n - 1, Math.floor(n * 0.95))]),
      jankPct: Math.round((100 * this.d.filter((x) => x > JANK_MS).length) / n),
      worst: Math.round(sorted[n - 1]),
      n,
    }
  }
}
