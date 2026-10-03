import { SERVER_SCOPED_PREFIX, type KeyValue } from './session'

/**
 * The searches that led somewhere, newest first, shown in place of the trending chips (Android
 * SearchViewModel.addToRecentSearches: saved when a result is opened, a repeat moves to the top,
 * the screen shows the last 10). Server-scoped, as Android's ServerDataReset clears searches too.
 */
const KEY = `${SERVER_SCOPED_PREFIX}search.recent`
export const RECENT_SEARCH_MAX = 10

type Kv = Pick<KeyValue, 'getItem' | 'setItem'>

export function recentSearches(kv: Kv): string[] {
  try {
    const v = JSON.parse(kv.getItem(KEY) ?? '[]')
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x.trim() !== '') : []
  } catch { return [] }
}

export function recordSearch(kv: Kv, query: string): string[] {
  const q = query.trim()
  if (!q) return recentSearches(kv)
  const next = [q, ...recentSearches(kv).filter((x) => x.toLowerCase() !== q.toLowerCase())].slice(0, RECENT_SEARCH_MAX)
  try { kv.setItem(KEY, JSON.stringify(next)) } catch { /* storage full: the trending chips show instead */ }
  return next
}
