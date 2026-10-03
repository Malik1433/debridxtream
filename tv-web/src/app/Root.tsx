import { useEffect, useSyncExternalStore } from 'react'
import { appKey } from '../keys'
import { exitApp, type Platform } from '../platform'
import { recordLifecycle } from './perf/lifecycle'
import { App } from './App'
import type { AppController } from './controller'
import { gateFor } from './gate'
import { Activation } from './screens/Activation'
import { Setup } from './screens/Setup'
import { Syncing } from './screens/Syncing'

/**
 * The gate, in order: licence -> an account to play from -> a first catalogue -> the app. Once a
 * catalogue exists, a background refresh never takes the screen away (no focus stolen on refresh).
 */
export function Root({ platform, controller }: { platform: Platform; controller: AppController }) {
  const s = useSyncExternalStore(controller.subscribe, controller.snapshot)
  const gate = gateFor(s)
  const gated = gate.screen !== 'app'

  // The gate screens have nowhere to go "up" to: BACK leaves the app rather than doing nothing.
  useEffect(() => {
    if (!gated) return
    const onKey = (e: KeyboardEvent) => { if (appKey(e.keyCode, platform) === 'back') { e.preventDefault(); recordLifecycle(localStorage, 'exit (BACK)'); exitApp(platform) } }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [gated, platform])

  if (gate.screen === 'activation') {
    return <Activation reason={gate.reason} code={s.activationCode} registered={s.registered} />
  }
  if (gate.screen === 'setup') return <Setup code={s.activationCode} claimed={s.claimed} />
  if (gate.screen === 'syncing') return <Syncing sync={gate.sync} onRetry={() => controller.retrySync()} />
  return <App platform={platform} state={s} controller={controller} onSyncNow={() => controller.retrySync()} />
}
