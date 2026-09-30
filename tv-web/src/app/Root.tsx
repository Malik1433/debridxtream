import { useEffect, useSyncExternalStore } from 'react'
import { appKey } from '../keys'
import { exitApp, type Platform } from '../platform'
import { App } from './App'
import type { AppController } from './controller'
import { Activation } from './screens/Activation'
import { Setup } from './screens/Setup'
import { Syncing } from './screens/Syncing'

/**
 * The gate, in order: licence -> an account to play from -> a first catalogue -> the app. Once a
 * catalogue exists, a background refresh never takes the screen away (no focus stolen on refresh).
 */
export function Root({ platform, controller }: { platform: Platform; controller: AppController }) {
  const s = useSyncExternalStore(controller.subscribe, controller.snapshot)
  const gated = s.license.kind === 'locked' || !s.hasAccount ||
    (!(s.sync.kind === 'done' && s.sync.channels > 0) && (s.sync.kind === 'running' || s.sync.kind === 'error'))

  // The gate screens have nowhere to go "up" to: BACK leaves the app rather than doing nothing.
  useEffect(() => {
    if (!gated) return
    const onKey = (e: KeyboardEvent) => { if (appKey(e.keyCode, platform) === 'back') { e.preventDefault(); exitApp(platform) } }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [gated, platform])

  if (s.license.kind === 'locked') {
    return <Activation reason={s.license.reason} code={s.activationCode} registered={s.registered} />
  }
  if (!s.hasAccount) return <Setup code={s.activationCode} claimed={s.claimed} />
  const hasCatalogue = s.sync.kind === 'done' && s.sync.channels > 0
  if (!hasCatalogue && (s.sync.kind === 'running' || s.sync.kind === 'error')) {
    return <Syncing sync={s.sync} onRetry={() => controller.retrySync()} />
  }
  return <App platform={platform} state={s} onSyncNow={() => controller.retrySync()} />
}
