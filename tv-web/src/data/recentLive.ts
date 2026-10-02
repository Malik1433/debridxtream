import { SERVER_SCOPED_PREFIX, type KeyValue } from './session'

/**
 * The live channels watched lately, newest first, for Home (W4 QA P4). Server-scoped: stream ids
 * mean nothing on another provider, so the switch purge clears this too. Name and logo are kept so
 * Home can draw the row without reading 15,951 channels; no URL is ever stored.
 */
const KEY = `${SERVER_SCOPED_PREFIX}live.recent`
export const RECENT_LIVE_MAX = 12

export interface RecentChannel { id: string; name: string; icon: string }
type Kv = Pick<KeyValue, 'getItem' | 'setItem'>

export function recentChannels(kv: Kv): RecentChannel[] {
  try {
    const v = JSON.parse(kv.getItem(KEY) ?? '[]')
    return Array.isArray(v) ? v.filter((x) => x && typeof x.id === 'string').map((x) => ({ id: x.id, name: String(x.name ?? ''), icon: String(x.icon ?? '') })) : []
  } catch { return [] }
}

export function recordChannel(kv: Kv, c: RecentChannel): RecentChannel[] {
  const next = [c, ...recentChannels(kv).filter((x) => x.id !== c.id)].slice(0, RECENT_LIVE_MAX)
  try { kv.setItem(KEY, JSON.stringify(next)) } catch { /* storage full: Home just shows less */ }
  return next
}
