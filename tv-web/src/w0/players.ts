/* eslint-disable @typescript-eslint/no-explicit-any */
import mpegts from 'mpegts.js'
import { PlayMetrics } from './metrics'
import type { Platform } from '../platform'

declare const webapis: any

export interface TestPlayer {
  readonly name: string
  play(url: string): void
  stop(): void
  bufferedAheadMs(): number | null
  /** Where the picture actually is. The only honest stall signal on a player that fires no events. */
  positionMs(): number | null
  /** Try a slow-fill speed; returns what the player reports afterwards (design §5). */
  trySpeed(rate: number): string
}

type Report = (msg: string) => void

/** Samsung's own player. `.ts` live, HLS, MKV, HEVC in hardware. */
export class AvplayPlayer implements TestPlayer {
  readonly name = 'AVPlay (Samsung)'
  constructor(private readonly m: PlayMetrics, private readonly report: Report) {}

  play(url: string): void {
    const av = webapis.avplay
    this.stop()
    ;(document.getElementById('av') as HTMLElement).style.display = 'block'
    this.m.load()
    av.open(url)
    av.setDisplayRect(0, 0, 1920, 1080)
    av.setListener({
      onbufferingstart: () => this.m.buffering(),
      onbufferingcomplete: () => this.m.playing(),
      onstreamcompleted: () => this.report('stream ended'),
      onerror: (e: unknown) => this.report(`AVPlay error: ${String(e)}`),
      onevent: () => undefined,
      oncurrentplaytime: () => undefined,
    })
    av.prepareAsync(() => { av.play(); this.m.playing() }, (e: unknown) => this.report(`prepare failed: ${String(e)}`))
  }

  stop(): void {
    try { webapis.avplay.stop(); webapis.avplay.close() } catch { /* idle */ }
    ;(document.getElementById('av') as HTMLElement).style.display = 'none'
  }

  bufferedAheadMs(): number | null { return null }

  positionMs(): number | null {
    try { return Number(webapis.avplay.getCurrentTime()) } catch { return null }
  }

  trySpeed(rate: number): string {
    try {
      webapis.avplay.setSpeed(rate)
      return `setSpeed(${rate}) accepted`
    } catch (e) {
      return `setSpeed(${rate}) refused: ${String((e as any)?.name ?? e)}`
    }
  }
}

/** The browser's own <video>: VIDAA / LG native pipeline. No CORS needed for a plain src. */
export class VideoTagPlayer implements TestPlayer {
  readonly name = '<video> (TV native)'
  private readonly v = document.getElementById('video') as HTMLVideoElement
  constructor(private readonly m: PlayMetrics, private readonly report: Report) {
    this.v.addEventListener('waiting', () => this.m.buffering())
    this.v.addEventListener('playing', () => this.m.playing())
    this.v.addEventListener('error', () => this.report(`video error code ${this.v.error?.code ?? '?'}`))
  }

  play(url: string): void {
    this.stop()
    this.v.style.display = 'block'
    this.m.load()
    this.v.src = url
    this.v.play().catch((e) => ignoreAbort(e, this.report))
  }

  stop(): void {
    this.v.pause()
    this.v.removeAttribute('src')
    this.v.load()
    this.v.style.display = 'none'
  }

  bufferedAheadMs(): number | null { return aheadMs(this.v) }

  positionMs(): number | null { return Math.round(this.v.currentTime * 1000) }

  trySpeed(rate: number): string {
    this.v.playbackRate = rate
    return `playbackRate now ${this.v.playbackRate}`
  }
}

/** mpegts.js: `.ts` -> fMP4 over MSE. The web IPTV fallback; needs CORS (it fetches the bytes itself). */
export class MpegtsPlayer implements TestPlayer {
  readonly name = 'mpegts.js'
  private readonly v = document.getElementById('video') as HTMLVideoElement
  private p: mpegts.Player | null = null
  constructor(private readonly m: PlayMetrics, private readonly report: Report) {
    this.v.addEventListener('waiting', () => this.p && this.m.buffering())
    this.v.addEventListener('playing', () => this.p && this.m.playing())
  }

  play(url: string): void {
    this.stop()
    if (!mpegts.getFeatureList().mseLivePlayback) {
      this.report('mpegts.js: this TV has no MSE live playback')
      return
    }
    this.v.style.display = 'block'
    this.m.load()
    const p = mpegts.createPlayer({ type: 'mpegts', isLive: true, url }, { enableWorker: false, liveBufferLatencyChasing: false })
    p.on(mpegts.Events.ERROR, (type: string, detail: string) => this.report(`mpegts.js error: ${type} / ${detail}`))
    p.attachMediaElement(this.v)
    p.load()
    Promise.resolve(p.play()).catch((e) => ignoreAbort(e, this.report))
    this.p = p
  }

  stop(): void {
    if (this.p) {
      try { this.p.pause(); this.p.unload(); this.p.detachMediaElement(); this.p.destroy() } catch { /* gone */ }
      this.p = null
    }
    this.v.style.display = 'none'
  }

  bufferedAheadMs(): number | null { return aheadMs(this.v) }

  positionMs(): number | null { return Math.round(this.v.currentTime * 1000) }

  trySpeed(rate: number): string {
    this.v.playbackRate = rate
    return `playbackRate now ${this.v.playbackRate}`
  }
}

/**
 * A zap stops the old stream while its play() promise is still pending, which rejects it with an
 * AbortError (VIDAA W0: on every zap). That is the zap working, not a failure.
 */
function ignoreAbort(e: unknown, report: Report): void {
  if ((e as { name?: string })?.name === 'AbortError') return
  report(`play() refused: ${String(e)}`)
}

function aheadMs(v: HTMLVideoElement): number | null {
  const b = v.buffered
  if (!b.length) return null
  return Math.max(0, Math.round((b.end(b.length - 1) - v.currentTime) * 1000))
}

export function nativePlayer(p: Platform, m: PlayMetrics, report: Report): TestPlayer {
  return p === 'tizen' ? new AvplayPlayer(m, report) : new VideoTagPlayer(m, report)
}
