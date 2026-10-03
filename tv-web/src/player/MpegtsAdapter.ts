import mpegts from 'mpegts.js'
import type { PlayerAdapter, PlayerError, PlayerState } from './PlayerAdapter'

/** mpegts.js over MSE: live `.ts` -> fMP4. The web-TV player (W0: 10 min, no stop, on VIDAA). */
export class MpegtsAdapter implements PlayerAdapter {
  readonly name = 'mpegts.js'
  private p: mpegts.Player | null = null
  private stateCb: (s: PlayerState) => void = () => undefined
  private errorCb: (e: PlayerError) => void = () => undefined
  private infoCb: (i: { video?: string; audio?: string }) => void = () => undefined
  /**
   * What the stream actually carries, as mpegts.js reads its PMT. The reason this is kept: a
   * channel whose codecs MSE cannot take produces no picture AND no error - mpegts.js simply stops
   * demuxing - so the only way to say WHY a channel is unavailable is to name what it carries
   * (W3 QA 2026-09-30, the provider's 4K channels).
   */
  private info: { video?: string; audio?: string } = {}

  constructor(private readonly video: HTMLVideoElement) {
    video.addEventListener('waiting', () => this.stateCb('buffering'))
    video.addEventListener('playing', () => this.stateCb('ready'))
    video.addEventListener('ended', () => this.stateCb('ended'))
  }

  /** `video/audio` as the stream declares them, or null before the PMT has been read. */
  mediaInfo(): string | null {
    const { video, audio } = this.info
    return video || audio ? `${video ?? '?'} / ${audio ?? '?'}` : null
  }

  load(url: string, opts: { live: boolean } = { live: true }): void {
    this.stop()
    this.info = {}
    const p = mpegts.createPlayer({ type: 'mpegts', isLive: opts.live, url }, { enableWorker: false })
    p.on(mpegts.Events.ERROR, (type: string, detail: string, info?: { code?: number; msg?: string }) =>
      this.errorCb({ http: info?.code, network: type === mpegts.ErrorTypes.NETWORK_ERROR, message: `${type}/${detail}` }))
    p.on(mpegts.Events.MEDIA_INFO, (mi: { videoCodec?: string; audioCodec?: string }) => {
      this.info = { video: mi.videoCodec, audio: mi.audioCodec }
      if (this.p === p) this.infoCb(this.info)
    })
    p.attachMediaElement(this.video)
    p.load()
    this.p = p
    this.stateCb('buffering')
    this.play()
  }

  play(): void {
    if (!this.p) return
    Promise.resolve(this.p.play()).catch((e: { name?: string }) => {
      // A zap stops the old stream while its play() is pending: AbortError is the zap working.
      // NotSupportedError is a load that failed; mpegts.js reports that itself, with the HTTP status
      // the hold-on needs - reported here too it read as a hard failure and gave up (W3 smoke).
      if (e?.name !== 'AbortError' && e?.name !== 'NotSupportedError') this.errorCb({ network: false, message: `play(): ${String(e)}` })
    })
  }

  pause(): void { this.p?.pause() }

  stop(): void {
    const p = this.p
    this.p = null
    if (!p) return
    try { p.pause(); p.unload(); p.detachMediaElement(); p.destroy() } catch { /* already gone */ }
    this.stateCb('idle')
  }

  onState(cb: (s: PlayerState) => void): void { this.stateCb = cb }
  onError(cb: (e: PlayerError) => void): void { this.errorCb = cb }
  /** The stream's codecs, the moment mpegts.js has read them - before MSE has had a chance to refuse. */
  onMediaInfo(cb: (i: { video?: string; audio?: string }) => void): void { this.infoCb = cb }

  positionMs(): number | null { return this.p ? Math.round(this.video.currentTime * 1000) : null }

  bufferedAheadMs(): number | null {
    const b = this.video.buffered
    if (!b.length) return null
    return Math.max(0, Math.round((b.end(b.length - 1) - this.video.currentTime) * 1000))
  }

  /**
   * W4 QA R7: the decoder stopped drawing with data buffered. A seek a frame ahead inside what is
   * already buffered makes the media pipeline flush and restart decoding - the cheapest reset there
   * is, and it costs no provider connection.
   */
  nudge(): boolean {
    const v = this.video, b = v.buffered
    if (!this.p || !b.length) return false
    const to = v.currentTime + 0.1
    for (let i = 0; i < b.length; i++) if (to >= b.start(i) && to < b.end(i)) { v.currentTime = to; return true }
    return false
  }

  setSpeed(rate: number): boolean {
    this.video.playbackRate = rate
    return Math.abs(this.video.playbackRate - rate) < 0.001
  }

  /**
   * Why there is no picture, in numbers (W4 QA P2: the TV accepts this channel's codecs, yet
   * mpegts.js shows nothing and nobody knows why). Data arriving? Buffered? Frames decoded? Media
   * error? One line for the [live] panel - the retail TV gives no other way to ask.
   */
  diagnose(): string {
    const v = this.video
    const b = v.buffered
    const q = (v as HTMLVideoElement & { getVideoPlaybackQuality?: () => { totalVideoFrames: number; droppedVideoFrames: number } }).getVideoPlaybackQuality?.()
    let stats = ''
    try {
      const s = (this.p as unknown as { statisticsInfo?: { speed?: number; decodedFrames?: number; droppedFrames?: number } } | null)?.statisticsInfo
      if (s) stats = ` · net ${Math.round(s.speed ?? 0)} KB/s · lib frames ${s.decodedFrames ?? 0}`
    } catch { /* no stats */ }
    return `ready ${v.readyState} net ${v.networkState} · buffered ${b.length ? `${b.start(0).toFixed(1)}-${b.end(b.length - 1).toFixed(1)}s` : 'none'}` +
      ` · frames ${q ? `${q.totalVideoFrames}/${q.droppedVideoFrames} dropped` : 'n/a'} · err ${v.error ? v.error.code : '-'}${stats}`
  }

  destroy(): void { this.stop() }
}
