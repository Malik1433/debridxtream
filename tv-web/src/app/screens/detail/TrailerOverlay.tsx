import { pause as pauseNav, resume as resumeNav } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { pushBackHandler } from '../../backStack'

/**
 * The TMDB trailer, as Android plays it (YouTube). Full screen over the page; BACK closes it. The
 * iframe is never focused, so the remote's keys stay with the app (Samsung: BACK must work).
 * If a TV refuses YouTube's embed, the black frame says so and BACK still closes it.
 */
export function TrailerOverlay({ youtubeKey, onClose }: { youtubeKey: string; onClose: () => void }) {
  useEffect(() => { pauseNav(); return () => resumeNav() }, [])
  useEffect(() => pushBackHandler(() => { onClose(); return true }), [onClose])
  const src = `https://www.youtube.com/embed/${encodeURIComponent(youtubeKey)}?autoplay=1&controls=0&rel=0&modestbranding=1&playsinline=1`
  return createPortal(
    <div className="trailer-overlay">
      <iframe src={src} title="Trailer" allow="autoplay; encrypted-media" tabIndex={-1} />
      <div className="trailer-hint">BACK closes the trailer</div>
    </div>,
    document.getElementById('stage') ?? document.body,
  )
}
