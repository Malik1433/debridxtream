import { FocusContext, setFocus, useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import { useCallback, useEffect, useRef, useState } from 'react'
import { appKey } from '../keys'
import { handleBack } from './backStack'
import { exitApp, type Platform } from '../platform'
import type { AppController, AppState } from './controller'
import { ExitDialog } from './ExitDialog'
import { Focusable } from './Focusable'
import { Router, type Screen } from './router'
import { Home } from './screens/Home'
import { LiveScreen } from './screens/live/LiveScreen'
import { Placeholder } from './screens/Placeholder'
import { Settings } from './screens/Settings'

const NAV: Array<{ key: Screen; label: string }> = [
  { key: 'home', label: 'Home' },
  { key: 'live', label: 'Live TV' },
  { key: 'movies', label: 'Movies' },
  { key: 'series', label: 'Series' },
  { key: 'settings', label: 'Settings' },
]

export function App({ platform, state, controller, onSyncNow }: { platform: Platform; state: AppState; controller: AppController; onSyncNow: () => void }) {
  const router = useRef(new Router()).current
  const [screen, setScreen] = useState<Screen>('home')
  const [exitAsked, setExitAsked] = useState(false)
  const sidebar = useFocusable({ focusKey: 'sidebar', saveLastFocusedChild: true, trackChildren: true })
  const content = useFocusable({ focusKey: 'content', saveLastFocusedChild: false })

  const open = useCallback((s: Screen) => {
    router.open(s)
    setScreen(router.current)
  }, [router])

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

  useEffect(() => { void setFocus('nav-home') }, [])

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
          {screen === 'home' && <Home onOpen={open} state={state} />}
          {screen === 'live' && <LiveScreen platform={platform} controller={controller} />}
          {screen === 'movies' && <Placeholder title="Movies" phase="W4" onHome={goHome} />}
          {screen === 'series' && <Placeholder title="Series" phase="W4" onHome={goHome} />}
          {screen === 'settings' && <Settings platform={platform} state={state} onSyncNow={onSyncNow} onHome={goHome} />}
        </div>
      </FocusContext.Provider>
      {exitAsked && (
        <ExitDialog
          onStay={() => { setExitAsked(false); void setFocus('nav-home') }}
          onExit={() => exitApp(platform)}
        />
      )}
    </div>
  )
}
