/**
 * Port of Android PlayerRecoveryController for films and episodes (W4 QA R5): a dropped connection
 * does not end the film. Up to 5 reconnects, 1 / 2 / 4 / 8 / 8 s apart (retryBackoffDelayMs), each
 * resuming where the picture was; a buffer that has not filled in 25 s (TIMEOUT_MS) counts as a
 * failure too. Only an error that waiting cannot fix - the file or format itself - gives up at once.
 */
export const VOD_MAX_RETRIES = 5
export const VOD_BUFFER_TIMEOUT_MS = 25_000
/** Start a little before the point of failure so the viewer does not lose the line that was cut. */
export const RESUME_REWIND_MS = 2_000

const TERMINAL = /NOT_SUPPORTED|INVALID_URI|UNSUPPORTED|video error 4\b|NotSupportedError|no account/i

export const backoffMs = (attempt: number) => 1_000 * 2 ** Math.min(Math.max(attempt - 1, 0), 3)

export class VodRecovery {
  private attempts = 0
  private lastGoodMs = 0

  /** Called while the picture plays: remembers where to come back to. */
  progress(positionMs: number): void { if (positionMs > 1_000) this.lastGoodMs = positionMs }

  /** It plays again: the next outage gets the full allowance (Android resets on READY). */
  recovered(): void { this.attempts = 0 }

  /** What to do about a failure: reconnect after `delayMs` at `resumeMs`, or give up (null). */
  onFailure(message: string): { attempt: number; delayMs: number; resumeMs: number } | null {
    if (TERMINAL.test(message) || this.attempts >= VOD_MAX_RETRIES) return null
    const attempt = ++this.attempts
    return { attempt, delayMs: backoffMs(attempt), resumeMs: Math.max(0, this.lastGoodMs - RESUME_REWIND_MS) }
  }
}
