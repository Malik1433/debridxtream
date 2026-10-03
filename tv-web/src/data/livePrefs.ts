import type { KeyValue } from './session'

/**
 * Settings → Live TV → "Resume Last Channel" (Android SettingsPreferences.KEY_RESUME_LAST_LIVE).
 * Off by default, as on Android: Live TV opens on its first category and channel. A viewing
 * preference, not provider data, so it survives a provider switch.
 */
const KEY = 'dx.live.resumeLast'
type Kv = Pick<KeyValue, 'getItem' | 'setItem'>

export function resumeLastLive(kv: Kv): boolean {
  try { return kv.getItem(KEY) === '1' } catch { return false }
}

export function setResumeLastLive(kv: Kv, on: boolean): void {
  try { kv.setItem(KEY, on ? '1' : '0') } catch { /* storage blocked: the default stays */ }
}
