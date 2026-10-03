import { useEffect, useState } from 'react'
import type { LiveEngine, LiveStatus } from '../../../player/liveEngine'
import { keptLines, liveLogLines, onLiveLog } from '../../../player/liveLog'
import { thisTvCodecLine } from '../../../player/codecSupport'
import { learnedBad } from '../../../player/learnedAudio'

const sec = (ms: number | null) => (ms === null ? 'n/a' : `${(ms / 1000).toFixed(1)} s`)

/**
 * The player's own log, on the screen (green key; 0 on VIDAA). A retail Samsung gives no logs at
 * all, so this is the only way a QA run can read stalls, retries and which player is carrying the
 * picture. Never shows a URL: the engine's lines carry channel names and codecs, not addresses.
 */
export function LiveDebugPanel({ engine, status }: { engine: LiveEngine; status: LiveStatus }) {
  const [, tick] = useState(0)
  useEffect(() => {
    const id = setInterval(() => tick((n) => n + 1), 500)
    const off = onLiveLog(() => tick((n) => n + 1))
    return () => { clearInterval(id); off() }
  }, [])
  const d = engine.debug()
  return (
    <div className="live-debug">
      <div className="dbg-head">
        <b>{d.player}</b> · {status.kind} · pos {sec(d.positionMs)} · buffer {sec(d.aheadMs)} · speed {d.speed.toFixed(2)}x
        · stalls {d.stalls}/3 min{d.targetMs > 0 ? ` (wants ${sec(d.targetMs)})` : ''} · stops {engine.meter.count}
      </div>
      <div className="dbg-head">{thisTvCodecLine(learnedBad(localStorage), 'AVPlay')} · red = this channel on the other player</div>
      {liveLogLines().slice(-14).map((l, i) => <div key={i} className="dbg-line">{l}</div>)}
      {/* Kept across screens and restarts (round 6): errors, retries, player switches, pictures. */}
      <div className="dbg-head">kept (last 12 of {keptLines().length}):</div>
      {keptLines().slice(-12).map((l, i) => <div key={`k${i}`} className="dbg-line">{l}</div>)}
    </div>
  )
}
