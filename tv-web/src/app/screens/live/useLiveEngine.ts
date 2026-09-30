import { useEffect, useRef, useState, type RefObject } from 'react'
import { LiveEngine, browserClock, type LiveMedia, type LiveStatus } from '../../../player/liveEngine'
import { MpegtsAdapter } from '../../../player/MpegtsAdapter'

/**
 * One LiveEngine over one <video> for the life of the Live screen. Preview and fullscreen are the
 * SAME element and the same engine (Android lesson: a player per view split one outage into two
 * stall counts). Leaving the screen stops the stream - one provider connection, never a leftover.
 */
export function useLiveEngine(video: RefObject<HTMLVideoElement | null>): { engine: LiveEngine | null; status: LiveStatus } {
  const [status, setStatus] = useState<LiveStatus>({ kind: 'idle' })
  const engineRef = useRef<LiveEngine | null>(null)
  const [, setReady] = useState(false)

  useEffect(() => {
    const el = video.current
    if (!el) return
    const adapter = new MpegtsAdapter(el)
    const media: LiveMedia = {
      load: (url) => adapter.load(url, { live: true }),
      play: () => adapter.play(),
      pause: () => adapter.pause(),
      stop: () => adapter.stop(),
      positionMs: () => adapter.positionMs(),
      bufferedAheadMs: () => adapter.bufferedAheadMs(),
      setSpeed: (r) => adapter.setSpeed(r),
      onError: (cb) => adapter.onError(cb),
    }
    const engine = new LiveEngine(media, browserClock, (line) => console.info(`[live] ${line}`))
    engineRef.current = engine
    const off = engine.onStatus(setStatus)
    setReady(true)
    return () => {
      console.info(`[live] ${engine.meter.logLine('live')}`)
      off()
      engine.stop()
      adapter.destroy()
      engineRef.current = null
    }
  }, [video])

  return { engine: engineRef.current, status }
}
