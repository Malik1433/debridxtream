import { pause as pauseNav, resume as resumeNav } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { pushBackHandler } from '../../backStack'

/**
 * Our trailer frame on Firebase Hosting (admin-panel/trailer.html). YouTube refuses its embed without
 * a page Referer, and a packaged TV app has none (file://) - W4 QA R4's "video player configuration
 * error". Framed from our own site, the official embed gets that site as its referrer: the same fix
 * as Android's TrailerActivity (which gives its WebView an origin), and the trailer never leaves the
 * app - as Netflix, Prime and Disney+ play theirs (W4 QA round 6).
 */
export const TRAILER_PAGE = 'https://debridxtream-new.web.app/trailer.html'

/** The TMDB trailer, full screen over the page; BACK closes it. The frame is never focused, so the remote stays with the app. */
export function TrailerOverlay({ youtubeKey, onClose }: { youtubeKey: string; onClose: () => void }) {
  useEffect(() => { pauseNav(); return () => resumeNav() }, [])
  useEffect(() => pushBackHandler(() => { onClose(); return true }), [onClose])
  return createPortal(
    <div className="trailer-overlay">
      <iframe src={`${TRAILER_PAGE}?v=${encodeURIComponent(youtubeKey)}`} title="Trailer" allow="autoplay; encrypted-media" tabIndex={-1} />
      <div className="trailer-hint">BACK closes the trailer</div>
    </div>,
    document.getElementById('stage') ?? document.body,
  )
}
