import { languageName, type Track, type VodEvent, type VodPlayer } from './vodTypes'

interface AudioTrackLike { enabled: boolean; language: string; label: string }
interface AudioTrackListLike { length: number; [i: number]: AudioTrackLike }

/**
 * Movies and episodes on LG / VIDAA / a browser: the TV's own <video>. webOS decodes MKV with AC-3,
 * MP3 and DTS natively and exposes `audioTracks` (webOS 3.0+); subtitles come as `textTracks`.
 */
export class HtmlVod implements VodPlayer {
  readonly name = '<video>'
  private cb: (e: VodEvent) => void = () => undefined
  private startMs = 0
  private text: number | null = null
  private readonly handlers: Array<[string, () => void]>

  constructor(private readonly video: HTMLVideoElement) {
    this.handlers = [
      ['waiting', () => this.cb({ type: 'buffering' })],
      ['playing', () => this.cb({ type: 'playing' })],
      ['ended', () => this.cb({ type: 'ended' })],
      ['error', () => this.cb({ type: 'error', message: `video error ${this.video.error?.code ?? '?'}` })],
      ['loadedmetadata', () => {
        if (this.startMs > 0) try { this.video.currentTime = this.startMs / 1000 } catch { /* not seekable */ }
        this.cb({ type: 'ready' })
      }],
    ]
    this.handlers.forEach(([ev, fn]) => video.addEventListener(ev, fn))
  }

  open(url: string, startMs: number): void {
    this.startMs = startMs
    this.text = null
    this.video.src = url
    this.video.style.visibility = 'visible'
    this.play()
  }

  play(): void {
    Promise.resolve(this.video.play()).catch((e: { name?: string }) => {
      if (e?.name !== 'AbortError') this.cb({ type: 'error', message: `play: ${e?.name ?? String(e)}` })
    })
  }

  pause(): void { this.video.pause() }

  stop(): void {
    this.video.pause()
    this.video.removeAttribute('src')
    try { this.video.load() } catch { /* nothing loaded */ }
  }

  seekTo(ms: number): void { try { this.video.currentTime = Math.max(0, ms / 1000) } catch { /* not seekable */ } }
  positionMs(): number { return Math.round((this.video.currentTime || 0) * 1000) }
  durationMs(): number { const d = this.video.duration; return Number.isFinite(d) ? Math.round(d * 1000) : 0 }
  playing(): boolean { return !this.video.paused && !this.video.ended }

  private audioList(): AudioTrackListLike | null {
    return (this.video as unknown as { audioTracks?: AudioTrackListLike }).audioTracks ?? null
  }

  tracks(): Track[] {
    const out: Track[] = []
    const a = this.audioList()
    if (a) for (let i = 0; i < a.length; i++) out.push({ kind: 'audio', index: i, label: a[i].label || languageName(a[i].language) || `Track ${i + 1}`, codec: '' })
    const t = this.video.textTracks
    for (let i = 0; i < (t?.length ?? 0); i++) {
      if (t[i].kind === 'subtitles' || t[i].kind === 'captions') out.push({ kind: 'text', index: i, label: t[i].label || languageName(t[i].language) || `Subtitles ${i + 1}`, codec: '' })
    }
    return out
  }

  selectTrack(kind: Track['kind'], index: number | null): void {
    if (kind === 'audio' && index !== null) {
      const a = this.audioList()
      if (a) for (let i = 0; i < a.length; i++) a[i].enabled = i === index
    }
    if (kind === 'text') {
      const t = this.video.textTracks
      for (let i = 0; i < (t?.length ?? 0); i++) t[i].mode = i === index ? 'showing' : 'disabled'
      this.text = index
    }
  }

  selected(kind: Track['kind']): number | null {
    if (kind === 'text') return this.text
    const a = this.audioList()
    if (a) for (let i = 0; i < a.length; i++) if (a[i].enabled) return i
    return null
  }

  onEvent(cb: (e: VodEvent) => void): void { this.cb = cb }

  destroy(): void {
    this.stop()
    this.handlers.forEach(([ev, fn]) => this.video.removeEventListener(ev, fn))
  }
}
