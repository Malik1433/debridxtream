import { InterruptionMeter } from '../policy/interruptionMeter'
import { RebufferPatience, SlowFillPolicy } from '../policy/liveCushion'
import { LiveHoldOn } from '../policy/liveHoldOn'

/** What the engine needs from a player. MpegtsAdapter provides it; tests provide a fake. */
export interface LiveMedia {
  load(url: string): void
  play(): void
  pause(): void
  stop(): void
  /** Playback position; the engine trusts THIS, not the player's events (W0: AVPlay froze silently). */
  positionMs(): number | null
  bufferedAheadMs(): number | null
  setSpeed(rate: number): boolean
  onError(cb: (e: { http?: number; network: boolean; message: string }) => void): void
  /** What the stream declares it carries, once known: names the cause when nothing can decode it. */
  mediaInfo?(): string | null
}

export interface LiveChannel { id: string; name: string; url: string }

export type LiveStatus =
  | { kind: 'idle' }
  | { kind: 'connecting'; channel: LiveChannel; slow: boolean }
  | { kind: 'playing'; channel: LiveChannel }
  | { kind: 'buffering'; channel: LiveChannel; waitingForMs: number }
  | { kind: 'reconnecting'; channel: LiveChannel; inMs: number }
  | { kind: 'failed'; channel: LiveChannel; message: string }

export interface Clock {
  now(): number
  every(ms: number, fn: () => void): () => void
  after(ms: number, fn: () => void): () => void
}

export const TICK_MS = 500
/** A position still this long while it should play is a stall, whatever the player says. */
export const STUCK_MS = 1_500
/** Media3's usual rebuffer threshold on Android live (2-3 s); the patience asks for more. */
export const BASE_REBUFFER_MS = 2_000
export const SLOW_LINE_AFTER_MS = 6_000
/**
 * A channel that never shows a picture is not "still connecting", it is not available - and an
 * unbounded wait is exactly the hang CLAUDE.md forbids ("Bound every network call... a timeout must
 * degrade, never hang"). W3 QA 2026-09-30: some 4K channels sat on "Connecting…" for ever, so the
 * viewer had nothing to act on. Generous, because a good channel here takes 3-5 s.
 */
export const CONNECT_TIMEOUT_MS = 25_000
export const SLOW_FILL_EVERY_MS = 3_000
/** A hold whose buffer has not grown for this long is a dead connection: reconnect. */
export const DEAD_HOLD_MS = 15_000
export const QUICK_RETRIES = [1_000, 2_000]

/**
 * One live channel on one player, with Android 3.4.1's anti-buffer rules (W3a) wired in:
 * a stall is a still position; while stalled the video is held until the patience says the cushion
 * is enough (5 s on a second stall, 8 s on a third, never over 10 s); under 5 s of buffer it plays
 * at 0.97x; an error retries twice quickly and then holds on (5/10/20/30 s for 30 min). Every stop
 * the viewer did not ask for is counted. One provider connection at a time: a zap stops the old
 * stream before the new one opens (the adapter's load does).
 */
export class LiveEngine {
  readonly patience: RebufferPatience
  readonly meter: InterruptionMeter
  private readonly slowFill = new SlowFillPolicy()
  private readonly holdOn: LiveHoldOn
  private channel: LiveChannel | null = null
  private status: LiveStatus = { kind: 'idle' }
  private listeners = new Set<(s: LiveStatus) => void>()
  private stopTick: (() => void) | null = null
  private cancelRetry: (() => void) | null = null
  private loadedAt = 0
  private started = false
  private lastPos = -1
  private lastPosAt = -1
  private holding = false
  private holdAhead = 0
  private holdAheadAt = 0
  private lastSpeedAt = 0
  private quickRetry = 0

  constructor(private readonly media: LiveMedia, private readonly clock: Clock, private readonly log: (line: string) => void = () => undefined) {
    this.patience = new RebufferPatience(clock.now)
    this.meter = new InterruptionMeter(clock.now)
    this.holdOn = new LiveHoldOn(clock.now)
    this.patience.onPatient = (n, target) => log(`live cushion: stall ${n} in 3 min, ${target > 0 ? `waiting for ${target / 1000}s of buffer` : 'usual threshold'}`)
    media.onError((e) => this.onError(e))
  }

  onStatus(cb: (s: LiveStatus) => void): () => void { this.listeners.add(cb); cb(this.status); return () => this.listeners.delete(cb) }
  current(): LiveStatus { return this.status }

  /** The viewer chose [c] (open or zap). */
  play(c: LiveChannel): void {
    this.meter.onUserChange()
    this.patience.onStream(c.id)
    this.slowFill.reset()
    this.holdOn.onRecovered()
    this.quickRetry = 0
    this.channel = c
    this.open()
  }

  stop(): void {
    this.cancelRetry?.(); this.cancelRetry = null
    this.stopTick?.(); this.stopTick = null
    this.meter.onUserChange()
    this.media.stop()
    this.channel = null
    this.set({ kind: 'idle' })
  }

  private open(): void {
    const c = this.channel
    if (!c) return
    this.cancelRetry?.(); this.cancelRetry = null
    this.media.setSpeed(1)
    this.loadedAt = this.clock.now()
    this.started = false
    this.holding = false
    this.lastPos = -1
    this.lastPosAt = -1
    this.media.load(c.url)
    this.media.play()
    this.set({ kind: 'connecting', channel: c, slow: false })
    this.stopTick?.()
    this.stopTick = this.clock.every(TICK_MS, () => this.tick())
  }

  private tick(): void {
    const c = this.channel
    if (!c) return
    const now = this.clock.now()
    const pos = this.media.positionMs()
    const ahead = this.media.bufferedAheadMs() ?? 0
    // The first reading is a baseline, not movement: a fresh stream reports 0 before any picture.
    const advancing = pos !== null && this.lastPos >= 0 && pos > this.lastPos
    if (pos !== null && (advancing || this.lastPos < 0)) { this.lastPos = pos; if (advancing) this.lastPosAt = now }

    if (this.holding) return this.tickHold(c, now, ahead)

    if (advancing) {
      if (this.status.kind !== 'playing') this.becamePlaying(c)
      if (now - this.lastSpeedAt >= SLOW_FILL_EVERY_MS) {
        this.lastSpeedAt = now
        const want = this.slowFill.speedFor(ahead)
        this.media.setSpeed(want)
      }
      return
    }
    if (!this.started) {
      if (now - this.loadedAt >= CONNECT_TIMEOUT_MS) return this.failToStart(c)
      const slow = now - this.loadedAt >= SLOW_LINE_AFTER_MS
      if (this.status.kind === 'connecting' && this.status.slow !== slow) this.set({ kind: 'connecting', channel: c, slow })
      return
    }
    if (this.lastPosAt >= 0 && now - this.lastPosAt >= STUCK_MS) this.startHold(c, now, ahead)
  }

  /** No picture at all, and no error either: the provider simply never sent one. Say so and stop. */
  private failToStart(c: LiveChannel): void {
    this.stopTick?.(); this.stopTick = null
    this.media.stop()
    const carries = this.media.mediaInfo?.() ?? null
    this.log(`live: no picture in ${CONNECT_TIMEOUT_MS} ms on ${c.name}${carries ? ` - stream carries ${carries}` : ' - nothing read from the stream'}`)
    this.set({
      kind: 'failed',
      channel: c,
      message: carries ? `${carries} did not play` : `no picture in ${Math.round(CONNECT_TIMEOUT_MS / 1000)} s`,
    })
  }

  private startHold(c: LiveChannel, now: number, ahead: number): void {
    this.meter.onStopped(true)
    this.holding = true
    this.holdAhead = ahead
    this.holdAheadAt = now
    this.media.pause()
    this.set({ kind: 'buffering', channel: c, waitingForMs: 0 })
  }

  private tickHold(c: LiveChannel, now: number, ahead: number): void {
    if (ahead > this.holdAhead + 250) { this.holdAhead = ahead; this.holdAheadAt = now }
    if (now - this.holdAheadAt >= DEAD_HOLD_MS) {
      this.onError({ network: true, message: 'no data while buffering' })
      return
    }
    if (this.patience.shouldStart(ahead, true, ahead >= BASE_REBUFFER_MS)) {
      this.holding = false
      this.lastPosAt = now // give the resumed picture a fresh STUCK_MS
      this.media.play()
      return
    }
    this.set({ kind: 'buffering', channel: c, waitingForMs: this.patience.targetMs() })
  }

  private becamePlaying(c: LiveChannel): void {
    this.started = true
    this.meter.onPlaying()
    this.holdOn.onRecovered()
    this.quickRetry = 0
    this.set({ kind: 'playing', channel: c })
  }

  private onError(e: { http?: number; network: boolean; message: string }): void {
    const c = this.channel
    if (!c) return
    this.meter.onStopped(true)
    this.stopTick?.(); this.stopTick = null
    this.holding = false
    const quick = e.network && this.quickRetry < QUICK_RETRIES.length ? QUICK_RETRIES[this.quickRetry++] : null
    const delay = quick ?? this.holdOn.nextRetryDelayMs(e.http, !e.network)
    this.log(`live error: ${e.message}${delay === null ? ' - giving up' : ` - retry in ${delay} ms`}`)
    if (delay === null) {
      this.media.stop()
      this.set({ kind: 'failed', channel: c, message: e.http ? `HTTP ${e.http}` : e.message })
      return
    }
    this.patience.expectRecoveryStart()
    this.set({ kind: 'reconnecting', channel: c, inMs: delay })
    this.cancelRetry = this.clock.after(delay, () => { this.cancelRetry = null; this.open() })
  }

  private set(s: LiveStatus): void {
    this.status = s
    this.listeners.forEach((l) => l(s))
  }
}

export const browserClock: Clock = {
  now: () => Date.now(),
  every: (ms, fn) => { const id = setInterval(fn, ms); return () => clearInterval(id) },
  after: (ms, fn) => { const id = setTimeout(fn, ms); return () => clearTimeout(id) },
}
