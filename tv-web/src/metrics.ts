/**
 * What W0 needs to know about one channel on one player: how long to the first picture, how often
 * it stopped afterwards and for how long. Same meaning as the Android interruption meter. Pure.
 */
export class PlayMetrics {
  private loadAt = -1
  private firstPlayAt = -1
  private stallSince = -1
  stalls = 0
  stalledMs = 0

  constructor(private readonly now: () => number) {}

  load(): void {
    this.loadAt = this.now()
    this.firstPlayAt = -1
    this.stallSince = -1
    this.stalls = 0
    this.stalledMs = 0
  }

  playing(): void {
    const t = this.now()
    if (this.firstPlayAt < 0) this.firstPlayAt = t
    if (this.stallSince >= 0) {
      this.stalledMs += t - this.stallSince
      this.stallSince = -1
    }
  }

  /** Buffering after the first picture is a stall; before it, it is still the start. */
  buffering(): void {
    if (this.firstPlayAt < 0 || this.stallSince >= 0) return
    this.stalls++
    this.stallSince = this.now()
  }

  get ttffMs(): number | null {
    return this.firstPlayAt < 0 || this.loadAt < 0 ? null : this.firstPlayAt - this.loadAt
  }

  get playedMs(): number {
    return this.firstPlayAt < 0 ? 0 : this.now() - this.firstPlayAt - this.currentStallMs() - this.stalledMs
  }

  private currentStallMs(): number {
    return this.stallSince < 0 ? 0 : this.now() - this.stallSince
  }

  summary(): string {
    const ttff = this.ttffMs
    return `first picture ${ttff === null ? '—' : `${(ttff / 1000).toFixed(1)} s`} · ` +
      `stops ${this.stalls} (${((this.stalledMs + this.currentStallMs()) / 1000).toFixed(1)} s) · ` +
      `played ${(this.playedMs / 1000).toFixed(0)} s`
  }
}
