/**
 * Port of LiveHoldOn.kt, "match mode": a Live channel does not give up while the viewer is on it.
 * After retries, try again after 5, 10, 20, 30, 30... s until it plays or 30 min pass. Give up at
 * once only when waiting cannot help: the account or channel is gone, or it is not the network.
 */
export const BACKOFF_MS = [5_000, 10_000, 20_000, 30_000]
export const MAX_HOLD_MS = 30 * 60_000
export const GIVE_UP_HTTP_CODES = [401, 404, 410, 451]

export class LiveHoldOn {
  private attempts = 0
  private holdingSince = -1
  constructor(private readonly now: () => number) {}

  /** Delay before trying the channel again, or null to give up and show the error. */
  nextRetryDelayMs(httpCode: number | undefined, notANetworkFailure: boolean): number | null {
    if (notANetworkFailure || (httpCode !== undefined && GIVE_UP_HTTP_CODES.includes(httpCode))) return null
    const t = this.now()
    if (this.holdingSince < 0) this.holdingSince = t
    if (t - this.holdingSince > MAX_HOLD_MS) return null
    return BACKOFF_MS[Math.min(this.attempts++, BACKOFF_MS.length - 1)]
  }

  /** A frame rendered: the next outage starts from the short pause. */
  onRecovered(): void { this.attempts = 0; this.holdingSince = -1 }
}

/** Port of NetworkGrace.kt: our own line down or back < 1 min ago says nothing about the feed. */
export const NETWORK_GRACE_MS = 60_000
export function networkRecentlyLost(online: boolean, lastLossAt: number, now: number): boolean {
  return !online || (lastLossAt > 0 && now - lastLossAt < NETWORK_GRACE_MS)
}
