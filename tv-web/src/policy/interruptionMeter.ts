/**
 * Port of PlaybackInterruptionMeter.kt: every stop the viewer did not ask for, and how long.
 * Not a pause, not the stop after the viewer's own zap, and one outage across player rebuilds is one.
 */
export class InterruptionMeter {
  count = 0
  stoppedMs = 0
  longestMs = 0
  private playingMs = 0
  private playingSince = -1
  private interruptedSince = -1
  private userChangePending = false
  constructor(private readonly now: () => number) {}

  onPlaying(): void {
    const t = this.now()
    this.closeInterruption(t)
    this.userChangePending = false
    if (this.playingSince < 0) this.playingSince = t
  }

  onStopped(viewerStillWantsPlay: boolean): void {
    const t = this.now()
    this.closePlaying(t)
    if (!viewerStillWantsPlay || this.userChangePending || this.interruptedSince >= 0) return
    this.count++
    this.interruptedSince = t
  }

  onUserChange(): void { this.closeInterruption(this.now()); this.userChangePending = true }

  logLine(mode: string): string {
    const t = this.now()
    this.closeInterruption(t); this.closePlaying(t)
    const perHour = this.playingMs > 0 ? Math.floor((this.count * 3_600_000) / this.playingMs) : 0
    return `interruptions mode=${mode} count=${this.count} stopped_ms=${this.stoppedMs} longest_ms=${this.longestMs} playing_ms=${this.playingMs} per_hour=${perHour}`
  }

  private closePlaying(t: number): void {
    if (this.playingSince < 0) return
    this.playingMs += t - this.playingSince
    this.playingSince = -1
  }

  private closeInterruption(t: number): void {
    if (this.interruptedSince < 0) return
    const lasted = t - this.interruptedSince
    this.stoppedMs += lasted
    this.longestMs = Math.max(this.longestMs, lasted)
    this.interruptedSince = -1
  }
}
