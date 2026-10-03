import { pause as pauseNav, resume as resumeNav } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { openInYouTubeApp } from '../../../platform'
import { pushBackHandler } from '../../backStack'
import { recordLifecycle } from '../../perf/lifecycle'

/**
 * The TMDB trailer, as Android plays it (YouTube). Full screen over the page; BACK closes it. The
 * iframe is never focused, so the remote's keys stay with the app (Samsung: BACK must work).
 * A Samsung refuses YouTube's embed inside a packaged app (W4 QA R4), so there the trailer goes to
 * the TV's YouTube app - Android TrailerActivity's own fallback - and the page says so if it cannot.
 */
export function TrailerOverlay({ youtubeKey, onClose }: { youtubeKey: string; onClose: () => void }) {
  const samsung = typeof (window as { tizen?: unknown }).tizen !== 'undefined'
  const [msg, setMsg] = useState(samsung ? 'Opening trailer in YouTube… DX Play stays open - come back to it from your apps.' : '')
  useEffect(() => { pauseNav(); return () => resumeNav() }, [])
  useEffect(() => pushBackHandler(() => { onClose(); return true }), [onClose])
  const close = useRef(onClose)
  close.current = onClose
  // Launch once per trailer, however often the page around it re-renders.
  useEffect(() => {
    if (!samsung) return
    let live = true
    recordLifecycle(localStorage, 'youtube: launching')
    void openInYouTubeApp(youtubeKey).then((ok) => {
      recordLifecycle(localStorage, ok ? 'youtube: launched' : 'youtube: no app')
      if (!live) return
      if (ok) close.current()
      else setMsg('This TV has no YouTube app to play the trailer.')
    })
    return () => { live = false }
  }, [samsung, youtubeKey])
  const src = `https://www.youtube.com/embed/${encodeURIComponent(youtubeKey)}?autoplay=1&controls=0&rel=0&modestbranding=1&playsinline=1`
  return createPortal(
    <div className="trailer-overlay">
      {samsung ? <div className="trailer-msg">{msg}</div> : <iframe src={src} title="Trailer" allow="autoplay; encrypted-media" tabIndex={-1} />}
      <div className="trailer-hint">BACK closes the trailer</div>
    </div>,
    document.getElementById('stage') ?? document.body,
  )
}
