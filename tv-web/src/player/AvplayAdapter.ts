/* eslint-disable @typescript-eslint/no-explicit-any */
import type { PlayerAdapter, PlayerError, PlayerState } from './PlayerAdapter'

declare const webapis: any

/** Samsung's AVPlay, drawn on its own plane under the page (the <object type="application/avwindow">). */
export class AvplayAdapter implements PlayerAdapter {
  readonly name = 'AVPlay'
  private open = false
  private stateCb: (s: PlayerState) => void = () => undefined
  private errorCb: (e: PlayerError) => void = () => undefined

  constructor(private readonly plane: HTMLElement) {}

  load(url: string): void {
    this.stop()
    const av = webapis.avplay
    this.plane.style.display = 'block'
    av.open(url)
    this.open = true
    av.setDisplayRect(0, 0, 1920, 1080)
    av.setListener({
      onbufferingstart: () => this.stateCb('buffering'),
      onbufferingcomplete: () => this.stateCb('ready'),
      onstreamcompleted: () => this.stateCb('ended'),
      onerror: (e: unknown) => this.errorCb({ network: true, message: String(e) }),
      onevent: () => undefined,
      oncurrentplaytime: () => undefined,
    })
    this.stateCb('buffering')
    av.prepareAsync(() => { av.play(); this.stateCb('ready') },
      (e: unknown) => this.errorCb({ network: true, message: `prepare: ${String(e)}` }))
  }

  play(): void { if (this.open) webapis.avplay.play() }
  pause(): void { if (this.open) webapis.avplay.pause() }

  stop(): void {
    if (!this.open) return
    this.open = false
    try { webapis.avplay.stop(); webapis.avplay.close() } catch { /* idle */ }
    this.plane.style.display = 'none'
    this.stateCb('idle')
  }

  onState(cb: (s: PlayerState) => void): void { this.stateCb = cb }
  onError(cb: (e: PlayerError) => void): void { this.errorCb = cb }
  bufferedAheadMs(): number | null { return null }

  setSpeed(rate: number): boolean {
    try { webapis.avplay.setSpeed(rate); return true } catch { return false }
  }

  destroy(): void { this.stop() }
}
