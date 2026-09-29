import mpegts from 'mpegts.js'
import type { PlayerAdapter, PlayerError, PlayerState } from './PlayerAdapter'

/** mpegts.js over MSE: live `.ts` -> fMP4. The web-TV player (W0: 10 min, no stop, on VIDAA). */
export class MpegtsAdapter implements PlayerAdapter {
  readonly name = 'mpegts.js'
  private p: mpegts.Player | null = null
  private stateCb: (s: PlayerState) => void = () => undefined
  private errorCb: (e: PlayerError) => void = () => undefined

  constructor(private readonly video: HTMLVideoElement) {
    video.addEventListener('waiting', () => this.stateCb('buffering'))
    video.addEventListener('playing', () => this.stateCb('ready'))
    video.addEventListener('ended', () => this.stateCb('ended'))
  }

  load(url: string, opts: { live: boolean }): void {
    this.stop()
    const p = mpegts.createPlayer({ type: 'mpegts', isLive: opts.live, url }, { enableWorker: false })
    p.on(mpegts.Events.ERROR, (type: string, detail: string, info?: { code?: number; msg?: string }) =>
      this.errorCb({ http: info?.code, network: type === mpegts.ErrorTypes.NETWORK_ERROR, message: `${type}/${detail}` }))
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
      if (e?.name !== 'AbortError') this.errorCb({ network: false, message: `play(): ${String(e)}` })
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

  bufferedAheadMs(): number | null {
    const b = this.video.buffered
    if (!b.length) return null
    return Math.max(0, Math.round((b.end(b.length - 1) - this.video.currentTime) * 1000))
  }

  setSpeed(rate: number): boolean {
    this.video.playbackRate = rate
    return Math.abs(this.video.playbackRate - rate) < 0.001
  }

  destroy(): void { this.stop() }
}
