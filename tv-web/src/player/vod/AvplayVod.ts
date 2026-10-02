/* eslint-disable @typescript-eslint/no-explicit-any */
import { parseAvplayTracks, playableAudioInstead, type Track, type VodEvent, type VodPlayer } from './vodTypes'

declare const webapis: any

/**
 * Movies and episodes on Samsung: AVPlay plays MKV/MP4 with AC-3, E-AC-3, multiple audio tracks and
 * embedded subtitles in hardware - none of which mpegts.js or the browser's <video> can. Rules from
 * Samsung's guides and the open-source Tizen players (design doc §11/§12):
 *  - setDisplayMethod FULL_SCREEN or the picture sits top-left at its own size;
 *  - the page must be transparent over the plane (html.avplay-on);
 *  - subtitles arrive as text through onsubtitlechange - we draw them;
 *  - DTS cannot play on 2018+ Samsung TVs: switch to another audio track, or say so.
 */
export class AvplayVod implements VodPlayer {
  readonly name = 'AVPlay'
  private cb: (e: VodEvent) => void = () => undefined
  private open_ = false
  private ready = false
  private isPlaying = false
  private gen = 0
  private audio: number | null = null
  private text: number | null = null
  private trackCache: Track[] | null = null

  constructor(private readonly plane: HTMLElement) {}

  open(url: string, startMs: number): void {
    this.stop()
    const av = webapis.avplay
    const gen = ++this.gen
    this.plane.style.display = 'block'
    document.documentElement.classList.add('avplay-on')
    try { av.open(url) } catch (e) { this.cb({ type: 'error', message: `open: ${errName(e)}` }); return }
    this.open_ = true
    try { av.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX') } catch { /* older model */ }
    try { av.setDisplayRect(0, 0, 1920, 1080) } catch { /* not open */ }
    av.setListener({
      onbufferingstart: () => { if (gen === this.gen) this.cb({ type: 'buffering' }) },
      onbufferingcomplete: () => { if (gen === this.gen) this.cb({ type: this.isPlaying ? 'playing' : 'ready' }) },
      onstreamcompleted: () => { if (gen === this.gen) { this.isPlaying = false; this.cb({ type: 'ended' }) } },
      onerror: (e: unknown) => { if (gen === this.gen) this.cb({ type: 'error', message: errName(e) }) },
      onsubtitlechange: (duration: unknown, text: unknown) => {
        if (gen === this.gen && this.text !== null) this.cb({ type: 'subtitle', text: String(text ?? ''), durationMs: Number(duration) || 3000 })
      },
      onevent: () => undefined,
      oncurrentplaytime: () => undefined,
    })
    av.prepareAsync(() => {
      if (gen !== this.gen || !this.open_) return
      this.ready = true
      this.trackCache = null
      this.fixDts()
      try { av.setSilentSubtitle(true) } catch { /* no subtitle support */ }
      const go = () => { try { av.play(); this.isPlaying = true; this.cb({ type: 'playing' }) } catch (e) { this.cb({ type: 'error', message: `play: ${errName(e)}` }) } }
      if (startMs > 0) {
        try { av.seekTo(startMs, go, go) } catch { go() }
      } else go()
      this.cb({ type: 'ready' })
    }, (e: unknown) => { if (gen === this.gen) this.cb({ type: 'error', message: `prepare: ${errName(e)}` }) })
  }

  /** On a TV without DTS, a DTS-only film plays silent: move to a track it can decode, or tell the viewer. */
  private fixDts(): void {
    const tracks = this.tracks()
    const cur = this.currentAudio()
    const instead = playableAudioInstead(tracks, cur)
    if (instead !== null) {
      this.selectTrack('audio', instead)
      this.cb({ type: 'notice', message: 'DTS audio is not supported on this TV - switched to another audio track' })
    } else if (tracks.some((t) => t.kind === 'audio') && tracks.filter((t) => t.kind === 'audio').every((t) => /dts|dca/i.test(t.codec))) {
      this.cb({ type: 'notice', message: 'This film only has DTS audio, which this TV cannot play' })
    }
  }

  private currentAudio(): number | null {
    if (this.audio !== null) return this.audio
    try {
      const cur = webapis.avplay.getCurrentStreamInfo?.() as Array<{ type: string; index: number }> | undefined
      return cur?.find((c) => String(c.type).toUpperCase() === 'AUDIO')?.index ?? null
    } catch { return null }
  }

  play(): void { if (this.ready) try { webapis.avplay.play(); this.isPlaying = true; this.cb({ type: 'playing' }) } catch { /* not playable now */ } }
  pause(): void { if (this.ready) try { webapis.avplay.pause(); this.isPlaying = false } catch { /* idle */ } }

  stop(): void {
    this.gen++
    if (!this.open_) return
    this.open_ = false
    this.ready = false
    this.isPlaying = false
    this.audio = null
    this.text = null
    try { webapis.avplay.stop() } catch { /* idle */ }
    try { webapis.avplay.close() } catch { /* idle */ }
    this.plane.style.display = 'none'
    document.documentElement.classList.remove('avplay-on')
  }

  seekTo(ms: number): void {
    if (!this.ready) return
    try { webapis.avplay.seekTo(Math.max(0, Math.round(ms)), () => undefined, () => undefined) } catch { /* not seekable */ }
  }

  positionMs(): number { try { return this.ready ? Number(webapis.avplay.getCurrentTime()) || 0 : 0 } catch { return 0 } }
  durationMs(): number { try { return this.ready ? Number(webapis.avplay.getDuration()) || 0 : 0 } catch { return 0 } }
  playing(): boolean { return this.isPlaying }

  tracks(): Track[] {
    if (!this.ready) return []
    if (this.trackCache) return this.trackCache
    try { this.trackCache = parseAvplayTracks(webapis.avplay.getTotalTrackInfo()) } catch { this.trackCache = [] }
    return this.trackCache
  }

  selectTrack(kind: Track['kind'], index: number | null): void {
    if (!this.ready) return
    const av = webapis.avplay
    try {
      if (kind === 'audio' && index !== null) { av.setSelectTrack('AUDIO', index); this.audio = index }
      if (kind === 'text') {
        if (index === null) { av.setSilentSubtitle(true); this.text = null; this.cb({ type: 'subtitle', text: '', durationMs: 0 }) }
        else { av.setSelectTrack('TEXT', index); av.setSilentSubtitle(false); this.text = index }
      }
    } catch (e) { this.cb({ type: 'notice', message: `Could not change track (${errName(e)})` }) }
  }

  selected(kind: Track['kind']): number | null { return kind === 'audio' ? this.currentAudio() : this.text }
  onEvent(cb: (e: VodEvent) => void): void { this.cb = cb }
  destroy(): void { this.stop() }
}

function errName(e: unknown): string {
  if (typeof e === 'string') return e
  const n = (e as { name?: string; message?: string })?.name ?? (e as { message?: string })?.message
  return n ? String(n) : String(e)
}
