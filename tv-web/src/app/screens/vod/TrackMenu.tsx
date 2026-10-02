import type { Track } from '../../../player/vod/vodTypes'

export interface MenuRow { kind: Track['kind']; index: number | null; label: string; on: boolean }

/** Audio and subtitle choices as one list the remote walks with ▲▼ and picks with OK. */
export function menuRows(tracks: Track[], audio: number | null, text: number | null): MenuRow[] {
  const a = tracks.filter((t) => t.kind === 'audio')
  const t = tracks.filter((x) => x.kind === 'text')
  return [
    ...a.map((x, i) => ({ kind: 'audio' as const, index: x.index, label: x.label, on: audio === null ? i === 0 : audio === x.index })),
    ...(t.length ? [{ kind: 'text' as const, index: null, label: 'Subtitles off', on: text === null }] : []),
    ...t.map((x) => ({ kind: 'text' as const, index: x.index, label: x.label, on: text === x.index })),
  ]
}

export function TrackMenu({ rows, focus, title }: { rows: MenuRow[]; focus: number; title?: string }) {
  return (
    <div className="track-menu">
      {title && <div className="tm-head">{title}</div>}
      {rows.length === 0 && <div className="tm-empty">{title === 'SUBTITLES' ? 'This video has no subtitles' : 'Nothing to choose here'}</div>}
      {rows.map((r, i) => (
        <div key={`${r.kind}-${r.index}-${i}`}>
          {!title && (i === 0 || rows[i - 1].kind !== r.kind) && <div className="tm-head">{r.kind === 'audio' ? 'Audio' : 'Subtitles'}</div>}
          <div className={`tm-row${i === focus ? ' focused' : ''}${r.on ? ' on' : ''}`}>{r.on ? '● ' : '○ '}{r.label}</div>
        </div>
      ))}
      <div className="tm-help">▲▼ choose · OK select · BACK close</div>
    </div>
  )
}
