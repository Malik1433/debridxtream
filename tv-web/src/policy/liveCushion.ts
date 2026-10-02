/**
 * Port of LiveCushionPolicy.kt (Android 3.4.1): live anti-buffer the viewer does not notice.
 * Same rules, same numbers - see the Android file for the QA history behind each line.
 *
 *  - RebufferPatience: a second stall within 3 min waits for 5 s of buffer, a third for 8 s, never
 *    longer than 10 s. One outage is one stall (a rebuffer and the reconnect after it are the same
 *    interruption); a feed switch keeps the count; a new channel resets it.
 *  - SlowFillPolicy: under 5 s of buffer play at 0.97x until 8 s.
 */
export const STALL_WINDOW_MS = 3 * 60_000
export const SECOND_STALL_TARGET_MS = 5_000
export const THIRD_STALL_TARGET_MS = 8_000
export const MAX_WAIT_MS = 10_000

export class RebufferPatience {
  private stallTimes: number[] = []
  private waitingSince = -1
  private streamKey: string | null = null
  private recoveryPending = false
  private feedSwitchPending = false
  private playedSinceLastStall = true

  /** Every counted stall, with the buffer it asks for (0 = the player's own threshold). */
  onPatient: ((stalls: number, targetMs: number) => void) | null = null

  constructor(private readonly now: () => number) {}

  /** A different stream: a new channel resets; the same channel on another feed does not. */
  onStream(key: string): void {
    if (key === this.streamKey) return
    this.streamKey = key
    if (this.feedSwitchPending) { this.feedSwitchPending = false; return }
    this.reset()
  }

  expectFeedSwitch(): void { this.feedSwitchPending = true }
  /** The app is about to rebuild the player to recover: its next start is a stall too. */
  expectRecoveryStart(): void { this.recoveryPending = true }

  reset(): void {
    this.stallTimes = []
    this.waitingSince = -1
    this.recoveryPending = false
    this.feedSwitchPending = false
    this.playedSinceLastStall = true
  }

  /** One "may I start?" check; [rebuffering] = the player stalled after playing. */
  shouldStart(bufferedMs: number, rebuffering: boolean, baseSaysGo: boolean): boolean {
    if (!rebuffering && !this.recoveryPending) return baseSaysGo
    const go = this.shouldResume(bufferedMs, baseSaysGo)
    if (go) this.recoveryPending = false
    return go
  }

  isWaitingFor(bufferedMs: number): boolean {
    return this.waitingSince >= 0 && bufferedMs < this.targetMs() && !this.waitedTooLong(this.now())
  }

  shouldResume(bufferedMs: number, baseSaysGo: boolean): boolean {
    const now = this.now()
    if (this.waitingSince < 0) {
      this.waitingSince = now
      if (this.playedSinceLastStall) {
        this.playedSinceLastStall = false
        this.stallTimes.push(now)
        this.stallTimes = this.stallTimes.filter((t) => now - t <= STALL_WINDOW_MS)
        this.onPatient?.(this.stallTimes.length, this.targetMs())
      }
    }
    const target = this.targetMs()
    const go = target <= 0 || this.waitedTooLong(now) ? baseSaysGo : baseSaysGo && bufferedMs >= target
    if (go) { this.waitingSince = -1; this.playedSinceLastStall = true }
    return go
  }

  /** Stalls counted in the last 3 minutes (the on-screen debug panel). */
  stallsInWindow(): number {
    const now = this.now()
    return this.stallTimes.filter((t) => now - t <= STALL_WINDOW_MS).length
  }

  targetMs(): number {
    if (this.stallTimes.length >= 3) return THIRD_STALL_TARGET_MS
    if (this.stallTimes.length === 2) return SECOND_STALL_TARGET_MS
    return 0
  }

  private waitedTooLong(now: number): boolean {
    return this.waitingSince >= 0 && now - this.waitingSince >= MAX_WAIT_MS
  }
}

export const SLOW_SPEED = 0.97
export const LOW_MS = 5_000
export const FULL_MS = 8_000

export class SlowFillPolicy {
  slow = false
  speedFor(bufferedMs: number): number {
    this.slow = this.slow ? bufferedMs < FULL_MS : bufferedMs < LOW_MS
    return this.slow ? SLOW_SPEED : 1
  }
  reset(): void { this.slow = false }
}
