import { doesFocusableExist, FocusContext, getCurrentFocusKey, setFocus, useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import { useCallback, useEffect, useRef, useState } from 'react'
import { appKey } from '../keys'
import { handleBack, pushBackHandler } from './backStack'
import { exitApp, type Platform } from '../platform'
import type { AppController, AppState } from './controller'
import { ExitDialog } from './ExitDialog'
import { Focusable } from './Focusable'
import { Router, type Screen } from './router'
import { Home } from './screens/Home'
import { LiveScreen } from './screens/live/LiveScreen'
import { Settings } from './screens/Settings'
import { MovieDetail } from './screens/detail/MovieDetail'
import { ShowDetail } from './screens/detail/ShowDetail'
import { LibraryScreen } from './screens/library/LibraryScreen'
import { markReturn } from './screens/library/focusMemory'
import { SearchScreen } from './screens/search/SearchScreen'
import { VodPlayerScreen } from './screens/vod/VodPlayerScreen'
import { episodeRequest, type PlayRequest } from './screens/vod/playRequest'
import type { Movie, Show } from '../data/vodApi'
import type { WatchEntry } from '../data/watchState'

const NAV: Array<{ key: Screen; label: string }> = [
  { key: 'home', label: 'Home' },
  { key: 'live', label: 'Live TV' },
  { key: 'movies', label: 'Movies' },
  { key: 'series', label: 'Series' },
  { key: 'search', label: 'Search' },
  { key: 'settings', label: 'Settings' },
]

type Detail = { kind: 'movie'; item: Movie } | { kind: 'show'; item: Show } | null
const LAST_SCREEN = 'dx.lastScreen'
const RESTORABLE: Screen[] = ['live', 'movies', 'series', 'search']

/** Samsung CO-GE-08: a TV switched off mid-film comes back where the viewer was, not on a blank Home. */
function lastScreen(): Screen {
  try { const s = localStorage.getItem(LAST_SCREEN) as Screen | null; return s && RESTORABLE.includes(s) ? s : 'home' } catch { return 'home' }
}

export function App({ platform, state, controller, onSyncNow }: { platform: Platform; state: AppState; controller: AppController; onSyncNow: () => void }) {
  const router = useRef(new Router()).current
  const [screen, setScreen] = useState<Screen>('home')
  const [exitAsked, setExitAsked] = useState(false)
  const [detail, setDetail] = useState<Detail>(null)
  const [play, setPlay] = useState<PlayRequest | null>(null)
  const [liveStart, setLiveStart] = useState<string | null>(null)
  const online = useOnline()
  const sidebar = useFocusable({ focusKey: 'sidebar', saveLastFocusedChild: true, trackChildren: true })
  const content = useFocusable({ focusKey: 'content', saveLastFocusedChild: false })

  const open = useCallback((s: Screen) => {
    router.open(s)
    setScreen(router.current)
    setDetail(null)
    try { localStorage.setItem(LAST_SCREEN, router.current) } catch { /* storage full or blocked */ }
  }, [router])

  // A detail page is one level under its list: BACK closes it and the list puts focus back.
  useEffect(() => {
    if (!detail) return
    return pushBackHandler(() => { markReturn(); setDetail(null); return true })
  }, [detail])

  /** "Continue watching": a film resumes at once; an episode needs its show's episode list for "next". */
  const onContinue = useCallback((e: WatchEntry) => {
    if (e.kind === 'movie') {
      setPlay({ kind: 'movie', id: e.id, ext: e.ext, title: e.title, subtitle: '', poster: e.poster, startMs: e.progressMs })
      return
    }
    const show = { id: e.seriesId ?? '', name: e.seriesName ?? '', poster: e.poster }
    const single: PlayRequest = { kind: 'episode', id: e.id, ext: e.ext, title: e.title, subtitle: `S${e.season} · E${e.episode}`, poster: e.poster,
      startMs: e.progressMs, seriesId: e.seriesId, seriesName: e.seriesName, season: e.season, episode: e.episode }
    if (!e.seriesId) { setPlay(single); return }
    controller.showInfo(e.seriesId).then((info) => {
      const ep = info.episodes.find((x) => x.id === e.id)
      setPlay(ep ? episodeRequest(ep, show, info.episodes, e.progressMs) : single)
    }).catch(() => setPlay(single))
  }, [controller])

  const goHome = useCallback(() => {
    open('home')
    void setFocus('nav-home')
  }, [open])

  // BACK goes up; at the top it asks to leave. Never a dead key (TV rulebook).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (appKey(e.keyCode, platform) !== 'back') return
      e.preventDefault()
      if (exitAsked) { setExitAsked(false); void setFocus('nav-home'); return }
      if (handleBack()) return
      if (router.back()) {
        setScreen(router.current)
        void setFocus(`nav-${router.current}`)
      } else {
        setExitAsked(true)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [platform, router, exitAsked])

  useEffect(() => {
    const s = lastScreen()
    if (s !== 'home') open(s)
    void setFocus(`nav-${s}`)
  }, [open])

  // Samsung multitasking guide: hidden during playback = what Return does - the film saves its
  // progress and closes, and the viewer comes back to its page (Live handles its own stream).
  useEffect(() => {
    const onVis = () => { if (document.hidden) setPlay(null) }
    document.addEventListener('visibilitychange', onVis)
    return () => document.removeEventListener('visibilitychange', onVis)
  }, [])

  /**
   * TV rulebook: focus is always visible, and the remote is never dead. A press towards a column
   * that has not rendered yet leaves the app with NO focus at all, and nothing brings it back - the
   * screens only re-check focus when they re-render, and a lost key press causes no render. W3 QA
   * 2026-09-30: opening Live TV while the 17,704-channel catalogue was still loading and pressing
   * right killed the remote until the viewer found their way back to Home.
   *
   * So every key press is followed by one check, after the navigation has been applied: put focus
   * back where it was, or failing that on this screen's own sidebar item, which always exists.
   */
  const lastGoodFocus = useRef('nav-home')
  useEffect(() => {
    // 'sidebar' and 'content' are the two containers. Focus can come to rest on one of them, which
    // exists and so looks healthy, while nothing inside it is focused and the arrows do nothing.
    const real = (k: string) => Boolean(k) && k !== 'sidebar' && k !== 'content' && doesFocusableExist(k)
    const onKey = () => {
      const before = getCurrentFocusKey()
      if (real(before)) lastGoodFocus.current = before
      setTimeout(() => {
        if (real(getCurrentFocusKey())) return
        const back = lastGoodFocus.current
        void setFocus(real(back) ? back : `nav-${router.current}`)
      }, 0)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [router])

  return (
    <div className="app">
      <FocusContext.Provider value={sidebar.focusKey}>
        <div ref={sidebar.ref} className="sidebar">
          <div className="brand">DX <span>Play</span></div>
          {NAV.map((n) => (
            <Focusable key={n.key} focusKey={`nav-${n.key}`}
              className={`nav-item${screen === n.key ? ' active' : ''}`} onEnter={() => open(n.key)}>
              {n.label}
            </Focusable>
          ))}
        </div>
      </FocusContext.Provider>
      <FocusContext.Provider value={content.focusKey}>
        <div ref={content.ref} className="content">
          {detail?.kind === 'movie' && <MovieDetail key={detail.item.id} movie={detail.item} controller={controller} onPlay={setPlay} />}
          {detail?.kind === 'show' && <ShowDetail key={detail.item.id} show={detail.item} controller={controller} onPlay={setPlay} />}
          {!detail && screen === 'home' && <Home onOpen={open} onContinue={onContinue} state={state} controller={controller} />}
          {!detail && screen === 'live' && <LiveScreen platform={platform} controller={controller} startChannelId={liveStart} onStarted={() => setLiveStart(null)} />}
          {!detail && screen === 'movies' && <LibraryScreen<Movie> kind="movies" controller={controller} state={state} onOpen={(m) => setDetail({ kind: 'movie', item: m })} />}
          {!detail && screen === 'series' && <LibraryScreen<Show> kind="shows" controller={controller} state={state} onOpen={(m) => setDetail({ kind: 'show', item: m })} />}
          {!detail && screen === 'search' && (
            <SearchScreen controller={controller} onChannel={(c) => { setLiveStart(c.id); open('live') }}
              onMovie={(m) => setDetail({ kind: 'movie', item: m })} onShow={(m) => setDetail({ kind: 'show', item: m })} />
          )}
          {!detail && screen === 'settings' && <Settings platform={platform} state={state} controller={controller} onSyncNow={onSyncNow} onHome={goHome} />}
        </div>
      </FocusContext.Provider>
      {!online && <div className="net-banner">No internet connection — DX Play will carry on when it is back.</div>}
      {play && <VodPlayerScreen key={`${play.kind}-${play.id}`} platform={platform} controller={controller} request={play} onClose={() => setPlay(null)} />}
      {exitAsked && (
        <ExitDialog
          onStay={() => { setExitAsked(false); void setFocus('nav-home') }}
          onExit={() => exitApp(platform)}
        />
      )}
    </div>
  )
}

/** Samsung CO-CN-02: when the network goes, say so - and say nothing while it is there. */
function useOnline(): boolean {
  const [on, setOn] = useState(() => (typeof navigator === 'undefined' ? true : navigator.onLine !== false))
  useEffect(() => {
    const up = () => setOn(true), down = () => setOn(false)
    window.addEventListener('online', up); window.addEventListener('offline', down)
    return () => { window.removeEventListener('online', up); window.removeEventListener('offline', down) }
  }, [])
  return on
}
