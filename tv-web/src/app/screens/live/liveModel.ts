import type { LiveCategory, LiveStream } from '../../../data/xtreamApi'

export const FAVOURITES_ID = '__fav'
export const ALL_ID = '__all'

export interface CategoryRow { id: string; name: string; count: number }

/** Favourites first (always there, so the yellow key has somewhere to show its result), then All, then the provider's own order. */
export function categoryRows(cats: LiveCategory[], streams: LiveStream[], favs: string[]): CategoryRow[] {
  const counts = new Map<string, number>()
  for (const s of streams) counts.set(s.categoryId, (counts.get(s.categoryId) ?? 0) + 1)
  const favSet = new Set(favs)
  return [
    { id: FAVOURITES_ID, name: 'Favourites', count: streams.filter((s) => favSet.has(s.id)).length },
    { id: ALL_ID, name: 'All channels', count: streams.length },
    // An empty category is a dead end for the remote: leave it out.
    ...cats.filter((c) => (counts.get(c.id) ?? 0) > 0).map((c) => ({ id: c.id, name: c.name, count: counts.get(c.id) ?? 0 })),
  ]
}

/** The channels of one category, in the provider's order (session sorts them). Favourites keep the order they were added in. */
export function channelsOf(categoryId: string, streams: LiveStream[], favs: string[]): LiveStream[] {
  if (categoryId === ALL_ID) return streams
  if (categoryId === FAVOURITES_ID) {
    const byId = new Map(streams.map((s) => [s.id, s]))
    return favs.map((id) => byId.get(id)).filter((s): s is LiveStream => s !== undefined)
  }
  return streams.filter((s) => s.categoryId === categoryId)
}

/** Where the viewer starts: their favourites if they have any, else the provider's first category. */
export function startCategory(rows: CategoryRow[]): number {
  if (rows[0]?.count > 0) return 0
  return rows.length > 2 ? 2 : Math.min(1, rows.length - 1)
}

/** Channel up/down, wrapping at both ends like a TV's own zap. */
export function zapIndex(current: number, delta: number, count: number): number {
  if (count <= 0) return -1
  return (((current + delta) % count) + count) % count
}

/** 0..1 through a programme, for the OSD bar. */
export function progress(start: number, end: number, now: number): number {
  if (end <= start) return 0
  return Math.min(1, Math.max(0, (now - start) / (end - start)))
}

export function clock(ms: number): string {
  const d = new Date(ms)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
