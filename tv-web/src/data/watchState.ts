import { SERVER_SCOPED_PREFIX, type KeyValue } from './session'

/**
 * Where the viewer got to in every movie and episode (Android: watched_state). Ids only mean
 * something on ONE provider, so this is server-scoped and the server-switch purge clears it.
 * No stream URL is stored - only ids; the URL is built from the session at play time.
 */
const KEY = `${SERVER_SCOPED_PREFIX}watch`
/** Android PlayerHistoryManager.COMPLETION_THRESHOLD_RATIO. */
export const WATCHED_RATIO = 0.9
/** Less than this and "Resume" would only skip the opening titles: start over instead. */
export const RESUME_MIN_MS = 30_000
const MAX_ENTRIES = 300

export interface WatchEntry {
  kind: 'movie' | 'episode'
  id: string
  title: string
  poster: string
  ext: string
  progressMs: number
  durationMs: number
  watched: boolean
  updatedAt: number
  /** Episodes: the series they belong to, so "Continue watching" can open the right show. */
  seriesId?: string
  seriesName?: string
  season?: number
  episode?: number
}

type Kv = Pick<KeyValue, 'getItem' | 'setItem'>

function load(kv: Kv): Record<string, WatchEntry> {
  try { const v = JSON.parse(kv.getItem(KEY) ?? '{}'); return v && typeof v === 'object' && !Array.isArray(v) ? v : {} } catch { return {} }
}

const keyOf = (kind: WatchEntry['kind'], id: string) => `${kind}:${id}`

export function watchEntry(kv: Kv, kind: WatchEntry['kind'], id: string): WatchEntry | null {
  return load(kv)[keyOf(kind, id)] ?? null
}

/** Record progress. Crossing 90% marks it watched, and it leaves "Continue watching". */
export function recordProgress(kv: Kv, e: Omit<WatchEntry, 'watched' | 'updatedAt'>, now = Date.now()): WatchEntry {
  const all = load(kv)
  const watched = e.durationMs > 0 && e.progressMs >= e.durationMs * WATCHED_RATIO
  const entry: WatchEntry = { ...e, watched, updatedAt: now }
  all[keyOf(e.kind, e.id)] = entry
  const keys = Object.keys(all)
  if (keys.length > MAX_ENTRIES) {
    keys.sort((a, b) => all[a].updatedAt - all[b].updatedAt).slice(0, keys.length - MAX_ENTRIES).forEach((k) => delete all[k])
  }
  kv.setItem(KEY, JSON.stringify(all))
  return entry
}

/** Where to start: the saved point if it is worth resuming, else 0. */
export function resumePointMs(e: WatchEntry | null): number {
  if (!e || e.watched || e.progressMs < RESUME_MIN_MS) return 0
  return e.progressMs
}

/** Started, not finished, newest first; one row per series (its latest episode). */
export function continueWatching(kv: Kv, limit = 20): WatchEntry[] {
  const seen = new Set<string>()
  return Object.values(load(kv))
    .filter((e) => !e.watched && e.progressMs >= RESUME_MIN_MS)
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .filter((e) => { const k = e.seriesId ? `s:${e.seriesId}` : `m:${e.id}`; if (seen.has(k)) return false; seen.add(k); return true })
    .slice(0, limit)
}

/** Episode ids of [seriesId] the viewer has finished (the episode list's ticks). */
export function watchedEpisodes(kv: Kv, seriesId: string): Map<string, WatchEntry> {
  const out = new Map<string, WatchEntry>()
  for (const e of Object.values(load(kv))) if (e.kind === 'episode' && e.seriesId === seriesId) out.set(e.id, e)
  return out
}

export function forget(kv: Kv, kind: WatchEntry['kind'], id: string): void {
  const all = load(kv)
  delete all[keyOf(kind, id)]
  kv.setItem(KEY, JSON.stringify(all))
}

/** Every entry, by `kind:id` - one parse for a whole grid of badges. */
export function allWatch(kv: Kv): Map<string, WatchEntry> {
  return new Map(Object.entries(load(kv)))
}
