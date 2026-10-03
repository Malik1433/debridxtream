import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import type { Platform } from '../../../platform'
import { AvplayAdapter, avplayAvailable, type Rect } from '../../../player/AvplayAdapter'
import { LiveEngine, browserClock, type LiveStatus } from '../../../player/liveEngine'
import { liveLog } from '../../../player/liveLog'
import { LiveMediaSwitch } from '../../../player/liveMediaSwitch'
import { MpegtsAdapter } from '../../../player/MpegtsAdapter'
import { canPlayAudio } from '../../../player/mseTypes'
import { learnBad, learnedBad } from '../../../player/learnedAudio'
import { decodedFrames, FROZEN_AFTER_MS, PictureWatch } from '../../../player/pictureWatch'

/**
 * Where [el] is on the SCREEN, in the 1920x1080 coordinates AVPlay's setDisplayRect takes. AVPlay
 * draws on the TV's own plane under the page, so this is window-relative, not stage-relative: with a
 * letterboxed stage the two differ (W4 QA R2). One factor from the width only - the screen's pixels
 * are square, and the height shrinks while the TV keyboard is open.
 */
function rectIn1080p(el: HTMLElement | null): Rect | null {
  if (!el) return null
  const r = el.getBoundingClientRect()
  const k = 1920 / (window.innerWidth || 1920)
  return { x: r.left * k, y: r.top * k, w: r.width * k, h: r.height * k }
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
    // W4 QA R7: log when the picture stops while the position runs on (MSE only - AVPlay has no
    // frame count). Round 5 caught it with 45 s buffered: the engine counts it and recovers (R7).
    const watch = new PictureWatch()
    const watcher = setInterval(() => {
      const d = engine.debug()
      const ev = watch.sample(Date.now(), d.positionMs, d.player === mse.name ? decodedFrames(el) : null)
      if (ev?.kind === 'frozen') {
        liveLog(`picture: FROZEN - no frame drawn for ${FROZEN_AFTER_MS / 1000} s while the position moved ${(ev.positionMovedMs / 1000).toFixed(1)} s`)
        engine.pictureFrozen()
      } else if (ev?.kind === 'thawed') {
        liveLog(`picture: drawing again after ${(ev.frozenForMs / 1000).toFixed(1)} s frozen`)
        engine.pictureThawed()
      }
    }, 500)
    setReady(true)
    return () => {
      clearInterval(watcher)
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
