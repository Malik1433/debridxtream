import type { Programme } from '../../../data/epg'
import { clock, progress } from './liveModel'

/** The fullscreen band: number, name, now (with its bar) and next. No guide is "no guide", never an error. */
export function LiveOsd({ number, name, favourite, programmes, now }: {
  number: number
  name: string
  favourite: boolean
  programmes: Programme[]
  now: number
}) {
  const [cur, next] = programmes
  return (
    <div className="osd">
      <div className="osd-title">
        <span className="osd-num">{number}</span>
        <span className="osd-name">{name}</span>
        {favourite && <span className="osd-fav">★</span>}
      </div>
      {cur ? (
        <>
          <div className="osd-now"><b>{clock(cur.start)}–{clock(cur.end)}</b> {cur.title}</div>
          <div className="osd-bar"><div style={{ width: `${Math.round(progress(cur.start, cur.end, now) * 100)}%` }} /></div>
          {next && <div className="osd-next"><b>Next {clock(next.start)}</b> {next.title}</div>}
        </>
      ) : (
        <div className="osd-next">No programme guide for this channel</div>
      )}
      <div className="osd-help">▲▼ change channel · OK hide · Yellow favourite · BACK list</div>
    </div>
  )
}
