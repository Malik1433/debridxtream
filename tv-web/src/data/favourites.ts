import type { KeyValue } from './session'
import { SERVER_SCOPED_PREFIX } from './session'

/**
 * Favourite Live channels. Keyed by stream id, which only means something on ONE provider - so it is
 * a server-scoped key (dx.srv.*), and the server-switch purge clears it with everything else.
 */
const KEY = `${SERVER_SCOPED_PREFIX}favourites`

export function favourites(kv: Pick<KeyValue, 'getItem'>): string[] {
  try { const v = JSON.parse(kv.getItem(KEY) ?? '[]'); return Array.isArray(v) ? v.map(String) : [] } catch { return [] }
}

export function toggleFavourite(kv: Pick<KeyValue, 'getItem' | 'setItem'>, id: string): string[] {
  const now = favourites(kv)
  const next = now.includes(id) ? now.filter((x) => x !== id) : [...now, id]
  kv.setItem(KEY, JSON.stringify(next))
  return next
}
