/**
 * What W0 needs to know about one channel on one player: how long to the first picture, how often
 * it stopped afterwards and for how long. Same meaning as the Android interruption meter. Pure.
 */
/** How long a still position counts as playing before it is called a stall. */
const STUCK_MS = 1_500

export class PlayMetrics {
  private loadAt = -1
  private firstPlayAt = -1
  private stallSince = -1
  stalls = 0
  stalledMs = 0
  private lastPos = -1
  private lastPosAt = -1

  constructor(private readonly now: () => number) {}

  load(): void {
    this.loadAt = this.now()
    this.firstPlayAt = -1
    this.stallSince = -1
    this.stalls = 0
    this.stalledMs = 0
    this.lastPos = -1
    this.lastPosAt = -1
  }

  playing(): void {
    const t = this.now()
    if (this.firstPlayAt < 0) this.firstPlayAt = t
    if (this.stallSince >= 0) {
      this.stalledMs += t - this.stallSince
      this.stallSince = -1
    }
  }

  /**
   * The truth the player's own events do not tell. Samsung W0: AVPlay froze the picture for good
   * while firing no buffering event at all, so the meter went on counting the freeze as playing.
   * A position that stops advancing IS a stall, whatever the player says about itself.
   */
  progress(positionMs: number | null): void {
    if (positionMs === null) return
    const t = this.now()
    if (positionMs > this.lastPos) {
      this.lastPos = positionMs
      this.lastPosAt = t
      this.playing()
      return
    }
    // Backdated to when the picture actually stopped, not to when we noticed: the grace window is
    // our detection lag, and charging it to play time would flatter every stall by STUCK_MS.
    if (this.lastPosAt >= 0 && t - this.lastPosAt >= STUCK_MS) this.stallFrom(this.lastPosAt)
  }

  /** Buffering after the first picture is a stall; before it, it is still the start. */
  buffering(): void {
    this.stallFrom(this.now())
  }

  private stallFrom(at: number): void {
    if (this.firstPlayAt < 0 || this.stallSince >= 0) return
    this.stalls++
    this.stallSince = at
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
