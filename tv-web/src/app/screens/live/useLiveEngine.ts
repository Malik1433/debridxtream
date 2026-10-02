import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import type { Platform } from '../../../platform'
import { AvplayAdapter, avplayAvailable, type Rect } from '../../../player/AvplayAdapter'
import { LiveEngine, browserClock, type LiveStatus } from '../../../player/liveEngine'
import { liveLog } from '../../../player/liveLog'
import { LiveMediaSwitch } from '../../../player/liveMediaSwitch'
import { MpegtsAdapter } from '../../../player/MpegtsAdapter'
import { canPlayAudio } from '../../../player/mseTypes'
import { learnBad, learnedBad } from '../../../player/learnedAudio'

/** Where [el] is on screen, in the 1920x1080 coordinates AVPlay's setDisplayRect takes. */
function rectIn1080p(el: HTMLElement | null): Rect | null {
  if (!el) return null
  // Relative to the stage (the 1920x1080 design), so the scale and any letterbox offset drop out.
  const st = document.getElementById('stage')?.getBoundingClientRect()
  const r = el.getBoundingClientRect()
  const k = st && st.width ? 1920 / st.width : 1920 / window.innerWidth
  return { x: (r.left - (st?.left ?? 0)) * k, y: (r.top - (st?.top ?? 0)) * k, w: r.width * k, h: r.height * k }
}

/**
 * One LiveEngine for the life of the Live screen, over mpegts.js on the page's <video> - and, on a
 * Samsung, AVPlay for the channels mpegts.js cannot carry. Preview and fullscreen are the SAME
 * engine (Android lesson: a player per view split one outage into two stall counts). Leaving the
 * screen stops the stream - one provider connection, never a leftover.
 */
export function useLiveEngine(video: RefObject<HTMLVideoElement | null>, box: RefObject<HTMLElement | null>, platform: Platform): {
  engine: LiveEngine | null
  status: LiveStatus
  /** The player box moved (fullscreen in or out): AVPlay's picture must follow it. */
  relayout: () => void
} {
  const [status, setStatus] = useState<LiveStatus>({ kind: 'idle' })
  const engineRef = useRef<LiveEngine | null>(null)
  const avRef = useRef<AvplayAdapter | null>(null)
  const [, setReady] = useState(false)

  useEffect(() => {
    const el = video.current
    if (!el) return
    const mse = new MpegtsAdapter(el)
    const plane = document.getElementById('av-plane')
    const av = platform === 'tizen' && plane && avplayAvailable() ? new AvplayAdapter(plane, () => rectIn1080p(box.current), liveLog) : null
    avRef.current = av
    // AVPlay draws UNDER the page: while it carries the picture the page must be see-through there.
    const onPlayer = (name: string) => {
      document.documentElement.classList.toggle('avplay-on', name === av?.name)
      liveLog(`player: ${name}`)
    }
    // What this TV has PROVED it cannot play wins over what MSE claims it can.
    const playable = (c: string | undefined) => !(c && learnedBad(localStorage).has(c.trim().toLowerCase())) && canPlayAudio(c)
    const learn = (c: string | undefined) => { if (learnBad(localStorage, c)) liveLog(`player: learned - audio ${c} does not play through mpegts.js on this TV`) }
    const media = new LiveMediaSwitch(mse, av, playable, liveLog, onPlayer, learn)
    const engine = new LiveEngine(media, browserClock, liveLog)
    engineRef.current = engine
    const off = engine.onStatus(setStatus)
    setReady(true)
    return () => {
      liveLog(engine.meter.logLine('live'))
      off()
      engine.stop()
      mse.destroy()
      av?.destroy()
      document.documentElement.classList.remove('avplay-on')
      engineRef.current = null
      avRef.current = null
    }
  }, [video, box, platform])

  const relayout = useCallback(() => avRef.current?.relayout(), [])
  return { engine: engineRef.current, status, relayout }
}
