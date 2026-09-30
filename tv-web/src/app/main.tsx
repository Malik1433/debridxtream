import { init, setKeyMap } from '@noriginmedia/norigin-spatial-navigation'
import { createRoot } from 'react-dom/client'
import { spatialKeyMap } from '../keys'
import { detectPlatform, registerKeys } from '../platform'
import { AppController } from './controller'
import { Root } from './Root'
import './styles.css'

const platform = detectPlatform()
registerKeys(platform)
// The stage is CSS-scaled: measure what is on screen, not offsetTop (which ignores the transform).
init({ throttle: 80, throttleKeypresses: true, useGetBoundingClientRect: true })
setKeyMap(spatialKeyMap(platform))

// One 1920x1080 design, scaled to whatever the TV reports (some report 1280x720).
const stage = document.createElement('div')
stage.id = 'stage'
document.getElementById('root')!.appendChild(stage)
const fit = () => { stage.style.transform = `scale(${window.innerWidth / 1920}, ${window.innerHeight / 1080})` }
fit()
window.addEventListener('resize', fit)

const controller = new AppController(platform)
controller.start()
createRoot(stage).render(<Root platform={platform} controller={controller} />)
