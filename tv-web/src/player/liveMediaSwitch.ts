import type { PlayerError } from './PlayerAdapter'
import type { LiveMedia } from './liveEngine'

/** The default player: mpegts.js over MSE (W0's winner). */
export interface PrimaryMedia {
  readonly name: string
  load(url: string): void
  play(): void
  pause(): void
  stop(): void
  positionMs(): number | null
  bufferedAheadMs(): number | null
  setSpeed(rate: number): boolean
  onError(cb: (e: PlayerError) => void): void
  onMediaInfo(cb: (i: { video?: string; audio?: string }) => void): void
  mediaInfo(): string | null
  diagnose?(): string
  nudge?(): boolean
}

/** The player for what the primary cannot carry (Samsung: AVPlay). It keeps its own buffer. */
export interface FallbackMedia {
  readonly name: string
  load(url: string): void
  play(): void
  pause(): void
  stop(): void
  positionMs(): number | null
  onError(cb: (e: PlayerError) => void): void
}

/**
 * mpegts.js first, Samsung's AVPlay for the channels it cannot carry (W3 QA 2026-10-01: non-AAC
 * audio never reaches MSE, so no picture and no error). Two ways in:
 *  - the stream's codecs arrive and this TV's MSE cannot take the audio: switch at once, rather than
 *    sit on "Connecting…" for 25 s;
 *  - the engine's connect timeout found no picture for any other reason (tryAlternative).
 * A channel that needed AVPlay goes straight to it next time (zap back, reconnect).
 * Never two provider connections: the old player is stopped before the other one opens.
 * Without a fallback (VIDAA, LG), unplayable audio fails fast with the reason instead.
 */
export class LiveMediaSwitch implements LiveMedia {
  private active: 'primary' | 'fallback' = 'primary'
  private url: string | null = null
  private readonly needsFallback = new Set<string>()
  private errorCb: (e: PlayerError) => void = () => undefined

  constructor(
    private readonly primary: PrimaryMedia,
    private readonly fallback: FallbackMedia | null,
    private readonly canPlayAudio: (codec: string | undefined) => boolean,
    private readonly log: (line: string) => void,
    private readonly onPlayer: (name: string) => void = () => undefined,
    /** Told the audio codec of a channel the primary could not start, so the next one switches at once. */
    private readonly learn: (audioCodec: string | undefined) => void = () => undefined,
  ) {
    primary.onError((e) => { if (this.active === 'primary') this.errorCb(e) })
    fallback?.onError((e) => { if (this.active === 'fallback') this.errorCb(e) })
    primary.onMediaInfo((i) => this.onMediaInfo(i))
  }

  load(url: string): void {
    this.url = url
    this.audio = undefined
    this.primary.stop()
    this.fallback?.stop()
    if (this.fallback && this.needsFallback.has(url)) return this.useFallback(url)
    this.setActive('primary')
    this.primary.load(url)
  }

  play(): void { this.current().play() }
  pause(): void { this.current().pause() }
  stop(): void { this.primary.stop(); this.fallback?.stop() }
  positionMs(): number | null { return this.current().positionMs() }
  bufferedAheadMs(): number | null { return this.active === 'primary' ? this.primary.bufferedAheadMs() : null }
  setSpeed(rate: number): boolean { return this.active === 'primary' ? this.primary.setSpeed(rate) : rate === 1 }
  onError(cb: (e: PlayerError) => void): void { this.errorCb = cb }
  mediaInfo(): string | null { return this.primary.mediaInfo() }
  reportsBuffer(): boolean { return this.active === 'primary' }
  diagnose(): string { return this.active === 'primary' ? this.primary.diagnose?.() ?? '' : '' }
  playerName(): string { return this.current().name }
  nudge(): boolean { return this.active === 'primary' ? this.primary.nudge?.() ?? false : false }

  tryAlternative(): boolean {
    if (this.active !== 'primary' || !this.fallback || !this.url) return false
    this.log(`player: ${this.primary.name} could not start this channel${this.audio ? ` (audio ${this.audio})` : ''} - moving it to ${this.fallback.name}`)
    this.learn(this.audio)
    this.needsFallback.add(this.url)
    this.primary.stop()
    this.useFallback(this.url)
    return true
  }

  /**
   * The stream's audio is not AAC and still no picture: on Samsung that is the MP3 / AC-3 case even
   * when MSE said it could take it. The engine asks this after a few seconds instead of 25.
   */
  prefersAlternative(): boolean {
    return this.active === 'primary' && this.fallback !== null && Boolean(this.audio) && !/^mp4a\.40/i.test(this.audio ?? '')
  }

  private audio: string | undefined

  private onMediaInfo(i: { video?: string; audio?: string }): void {
    this.audio = i.audio
    if (this.active !== 'primary' || !this.url || this.canPlayAudio(i.audio)) return
    if (!this.fallback) {
      this.primary.stop()
      this.errorCb({ network: false, message: `audio ${i.audio} is not supported on this TV` })
      return
    }
    // Not "the TV cannot decode it": the TV may well (W4 QA N1 - it reports AC-3 through MSE). What
    // is known is narrower: it does not play through mpegts.js here, by MSE's answer or by a
    // channel that already failed.
    this.log(`player: audio ${i.audio} does not play through ${this.primary.name} on this TV - playing it on ${this.fallback.name}`)
    this.needsFallback.add(this.url)
    this.primary.stop()
    this.useFallback(this.url)
  }

  private useFallback(url: string): void {
    this.setActive('fallback')
    this.fallback!.load(url)
  }

  private setActive(a: 'primary' | 'fallback'): void {
    if (this.active === a) return
    this.active = a
    this.onPlayer(this.current().name)
  }

  private current(): PrimaryMedia | FallbackMedia { return this.active === 'fallback' && this.fallback ? this.fallback : this.primary }
}
