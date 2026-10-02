import type { KeyValue } from './session'
import { SERVER_SCOPED_PREFIX } from './session'

/**
 * Favourites: channels, movies and series. Keyed by ids, which only mean something on ONE provider -
 * so every list is a server-scoped key (dx.srv.*), and the server-switch purge clears them all.
 */
export type FavKind = 'live' | 'movies' | 'shows'
const KEYS: Record<FavKind, string> = {
  live: `${SERVER_SCOPED_PREFIX}favourites`, // the W3 key, unchanged
  movies: `${SERVER_SCOPED_PREFIX}fav.movies`,
  shows: `${SERVER_SCOPED_PREFIX}fav.shows`,
}

export function favourites(kv: Pick<KeyValue, 'getItem'>, kind: FavKind = 'live'): string[] {
  try { const v = JSON.parse(kv.getItem(KEYS[kind]) ?? '[]'); return Array.isArray(v) ? v.map(String) : [] } catch { return [] }
}

export function toggleFavourite(kv: Pick<KeyValue, 'getItem' | 'setItem'>, id: string, kind: FavKind = 'live'): string[] {
  const now = favourites(kv, kind)
  const next = now.includes(id) ? now.filter((x) => x !== id) : [...now, id]
  kv.setItem(KEYS[kind], JSON.stringify(next))
  return next
}
