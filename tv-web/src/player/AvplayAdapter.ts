/* eslint-disable @typescript-eslint/no-explicit-any */
import type { PlayerError } from './PlayerAdapter'

declare const webapis: any

export interface Rect { x: number; y: number; w: number; h: number }

/**
 * Seconds of stream AVPlay gathers before it first plays, and after a stall before it resumes.
 * W0 ran AVPlay on its defaults and it managed 13 s of picture in 108 s; these are the live `.ts`
 * numbers the design asked for. RESUME is the third-stall cushion of the mpegts.js path (8 s),
 * because this player gives the engine no buffer reading to be patient with (owner, 2026-10-02).
 */
export const AVPLAY_BUFFER_FOR_PLAY_S = 4
export const AVPLAY_BUFFER_FOR_RESUME_S = 8

/** Errors that are about the file, not the line: retrying them would only fail again. */
const NOT_NETWORK = ['PLAYER_ERROR_NOT_SUPPORTED_FILE', 'PLAYER_ERROR_NOT_SUPPORTED_FORMAT', 'PLAYER_ERROR_INVALID_URI']

/** Is Samsung's player here at all? (Tizen with webapis.js loaded.) */
export function avplayAvailable(): boolean {
  try { return typeof webapis !== 'undefined' && Boolean(webapis?.avplay?.open) } catch { return false }
}

/**
 * Samsung's AVPlay for live `.ts`: the fallback for channels mpegts.js cannot feed to MSE (W3 QA:
 * non-AAC audio). It plays raw TS itself, so MP3 / AC-3 are not its problem. It draws on a video
 * plane UNDER the page, inside the rect we give it; the page must be transparent there.
 */
export class AvplayAdapter {
  readonly name = 'AVPlay'
  private open = false
  private prepared = false
  /** Each load's number: a prepare that lands after a zap belongs to a stream we already left. */
  private gen = 0
  private errorCb: (e: PlayerError) => void = () => undefined

  constructor(private readonly plane: HTMLElement, private readonly rect: () => Rect | null, private readonly log: (line: string) => void) {}

  load(url: string): void {
    this.stop()
    const av = webapis.avplay
    this.plane.style.display = 'block'
    try {
      av.open(url)
    } catch (e) {
      this.errorCb({ network: true, message: `AVPlay open: ${errName(e)}` })
      return
    }
    this.open = true
    this.prepared = false
    this.relayout()
    for (const [kind, s] of [['PLAYER_BUFFER_FOR_PLAY', AVPLAY_BUFFER_FOR_PLAY_S], ['PLAYER_BUFFER_FOR_RESUME', AVPLAY_BUFFER_FOR_RESUME_S]] as const) {
      try { av.setBufferingParam(kind, 'PLAYER_BUFFER_SIZE_IN_SECOND', s) } catch (e) { this.log(`AVPlay: ${kind} ${s}s refused (${errName(e)})`) }
    }
    av.setListener({
      onbufferingstart: () => undefined,
      onbufferingcomplete: () => undefined,
      onstreamcompleted: () => this.errorCb({ network: true, message: 'AVPlay: stream ended' }),
      onerror: (e: unknown) => {
        const name = errName(e)
        this.errorCb({ network: !NOT_NETWORK.includes(name), message: `AVPlay ${name}` })
      },
      onevent: () => undefined,
      oncurrentplaytime: () => undefined,
    })
    const gen = ++this.gen
    av.prepareAsync(() => {
      if (!this.open || gen !== this.gen) return
      this.prepared = true
      try { av.play() } catch (e) { this.errorCb({ network: true, message: `AVPlay play: ${errName(e)}` }) }
    }, (e: unknown) => { if (this.open && gen === this.gen) this.errorCb({ network: true, message: `AVPlay prepare: ${errName(e)}` }) })
  }

  /** The engine calls play() right after load(); AVPlay plays by itself once prepared. */
  play(): void { if (this.prepared) try { webapis.avplay.play() } catch { /* not in a playable state */ } }
  pause(): void { if (this.prepared) try { webapis.avplay.pause() } catch { /* idle */ } }

  stop(): void {
    if (!this.open) return
    this.open = false
    this.prepared = false
    try { webapis.avplay.stop() } catch { /* idle */ }
    try { webapis.avplay.close() } catch { /* idle */ }
    this.plane.style.display = 'none'
  }

  /** Put the picture where the page's player box is (preview or fullscreen). */
  relayout(): void {
    if (!this.open) return
    const r = this.rect() ?? { x: 0, y: 0, w: 1920, h: 1080 }
    try { webapis.avplay.setDisplayRect(Math.round(r.x), Math.round(r.y), Math.round(r.w), Math.round(r.h)) } catch { /* not open */ }
  }

  onError(cb: (e: PlayerError) => void): void { this.errorCb = cb }

  positionMs(): number | null {
    if (!this.prepared) return null
    try { return Number(webapis.avplay.getCurrentTime()) } catch { return null }
  }

  destroy(): void { this.stop() }
}

function errName(e: unknown): string {
  if (typeof e === 'string') return e
  const n = (e as { name?: string; message?: string })?.name ?? (e as { message?: string })?.message
  return n ? String(n) : String(e)
}
