/** A movie or episode player, whatever sits underneath (AVPlay on Samsung, <video> elsewhere). */
export interface Track { kind: 'audio' | 'text'; index: number; label: string; codec: string }

export type VodEvent =
  | { type: 'ready' }
  | { type: 'buffering' }
  | { type: 'playing' }
  | { type: 'ended' }
  | { type: 'error'; message: string }
  /** A subtitle line to draw ourselves (AVPlay hands us the text; it does not draw it). */
  | { type: 'subtitle'; text: string; durationMs: number }
  | { type: 'notice'; message: string }

export interface VodPlayer {
  readonly name: string
  open(url: string, startMs: number): void
  play(): void
  pause(): void
  stop(): void
  seekTo(ms: number): void
  positionMs(): number
  durationMs(): number
  playing(): boolean
  tracks(): Track[]
  /** null = subtitles off. */
  selectTrack(kind: Track['kind'], index: number | null): void
  selected(kind: Track['kind']): number | null
  onEvent(cb: (e: VodEvent) => void): void
  destroy(): void
}

/** Samsung TVs from 2018 on cannot decode DTS: it plays silent (Samsung 2018 video specifications). */
export const isDts = (codec: string) => /dts|dca/i.test(codec)

const LANGS: Record<string, string> = {
  eng: 'English', en: 'English', ger: 'German', deu: 'German', de: 'German', spa: 'Spanish', es: 'Spanish', fre: 'French',
  fra: 'French', fr: 'French', ita: 'Italian', it: 'Italian', por: 'Portuguese', pt: 'Portuguese', ara: 'Arabic', ar: 'Arabic',
  hin: 'Hindi', hi: 'Hindi', urd: 'Urdu', ur: 'Urdu', tam: 'Tamil', ta: 'Tamil', tur: 'Turkish', tr: 'Turkish', dut: 'Dutch',
  nld: 'Dutch', nl: 'Dutch', rus: 'Russian', ru: 'Russian', pol: 'Polish', pl: 'Polish', und: 'Unknown',
}
export const languageName = (code: string) => LANGS[code.toLowerCase()] ?? (code ? code.toUpperCase() : '')

/**
 * AVPlay's getTotalTrackInfo(): [{type:'AUDIO'|'TEXT'|'VIDEO', index, extra_info:'{"language":"eng","fourCC":"AC3",...}'}].
 * extra_info is a JSON string on most models and an object on some - read both, never throw.
 */
export function parseAvplayTracks(raw: unknown): Track[] {
  if (!Array.isArray(raw)) return []
  const out: Track[] = []
  for (const t of raw) {
    const type = String(t?.type ?? '').toUpperCase()
    if (type !== 'AUDIO' && type !== 'TEXT') continue
    let x: Record<string, unknown> = {}
    try { x = typeof t.extra_info === 'string' ? JSON.parse(t.extra_info) : (t.extra_info ?? {}) } catch { x = {} }
    const lang = String(x.language ?? x.track_lang ?? '')
    const codec = String(x.fourCC ?? x.subtitle_type ?? '')
    const ch = Number(x.channels)
    const n = out.filter((o) => o.kind === (type === 'AUDIO' ? 'audio' : 'text')).length + 1
    const label = [languageName(lang) || `Track ${n}`, codec, ch > 2 ? `${ch === 6 ? '5.1' : ch === 8 ? '7.1' : `${ch} ch`}` : '']
      .filter(Boolean).join(' · ')
    out.push({ kind: type === 'AUDIO' ? 'audio' : 'text', index: Number(t.index), label, codec })
  }
  return out
}

/** The audio track to switch to when the one playing is DTS on a TV that cannot decode it. null = keep. */
export function playableAudioInstead(tracks: Track[], current: number | null): number | null {
  const audio = tracks.filter((t) => t.kind === 'audio')
  const cur = audio.find((t) => t.index === current) ?? audio[0]
  if (!cur || !isDts(cur.codec)) return null
  const other = audio.find((t) => !isDts(t.codec))
  return other ? other.index : null
}

/** "1:02:05" / "4:09". */
export function clockOf(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60
  const mm = h ? String(m).padStart(2, '0') : String(m)
  return `${h ? `${h}:` : ''}${mm}:${String(sec).padStart(2, '0')}`
}

/**
 * How far one ◀/▶ press jumps: holding the key (presses < 700 ms apart) speeds up, the way every TV
 * player does - 10 s, then 30 s, then 60 s.
 */
export function seekStep(repeats: number): number {
  return repeats < 3 ? 10_000 : repeats < 8 ? 30_000 : 60_000
}
