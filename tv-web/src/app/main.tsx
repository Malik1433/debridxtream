import { getCurrentFocusKey, init, setKeyMap } from '@noriginmedia/norigin-spatial-navigation'
import { createRoot } from 'react-dom/client'
import { spatialKeyMap } from '../keys'
import { detectPlatform, registerKeys } from '../platform'
import { AppController } from './controller'
import { watchLifecycle } from './perf/lifecycle'
import { Root } from './Root'
import './styles.css'

const platform = detectPlatform()
registerKeys(platform)
// The stage is CSS-scaled: measure what is on screen, not offsetTop (which ignores the transform).
init({ throttle: 80, throttleKeypresses: true, useGetBoundingClientRect: true })
setKeyMap(spatialKeyMap(platform))
// QA aid: the focus key, readable from a remote inspector / headless smoke. Holds no data.
;(window as unknown as { __dxFocus: () => string }).__dxFocus = getCurrentFocusKey

// One 1920x1080 design, scaled to whatever the TV reports (some report 1280x720).
try { watchLifecycle(localStorage) } catch { /* storage blocked */ }
const stage = document.createElement('div')
stage.id = 'stage'
document.getElementById('root')!.appendChild(stage)
/**
 * ONE scale factor, never a squash (W4 QA N5: Samsung's keyboard takes the bottom of the screen,
 * innerHeight halves, and a separate Y scale flattened the whole app). A resize that only shrinks the
 * height - the keyboard opening - keeps the current scale; the keyboard covers the bottom instead.
 */
let fitted = { w: 0, h: 0 }
const fit = () => {
  const w = window.innerWidth, h = window.innerHeight
  if (fitted.w === w && h < fitted.h) return
  fitted = { w, h }
  const s = Math.min(w / 1920, h / 1080)
  stage.style.transform = `translate(${(w - 1920 * s) / 2}px, ${(h - 1080 * s) / 2}px) scale(${s})`
}
fit()
window.addEventListener('resize', fit)

const controller = new AppController(platform)
controller.start()
createRoot(stage).render(<Root platform={platform} controller={controller} />)
