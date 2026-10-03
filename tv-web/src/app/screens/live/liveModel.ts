import type { LiveCategory, LiveStream } from '../../../data/xtreamApi'

export const FAVOURITES_ID = '__fav'
export const ALL_ID = '__all'

export interface CategoryRow { id: string; name: string; count: number }

/**
 * Android LiveCategoryChips.buildDisplayCategories: Favorites, then the provider's categories - no
 * "All channels" chip on the TV. Empty categories are left out (a dead end for the remote).
 */
export function categoryRows(cats: LiveCategory[], streams: LiveStream[], favs: string[]): CategoryRow[] {
  const counts = new Map<string, number>()
  for (const s of streams) counts.set(s.categoryId, (counts.get(s.categoryId) ?? 0) + 1)
  const favSet = new Set(favs)
  return [
    { id: FAVOURITES_ID, name: 'Favorites', count: streams.filter((s) => favSet.has(s.id)).length },
    ...cats.filter((c) => (counts.get(c.id) ?? 0) > 0).map((c) => ({ id: c.id, name: c.name, count: counts.get(c.id) ?? 0 })),
  ]
}

/**
 * Android LiveCategoryChips.applyChipFilter: while the search pill has a query the chip strip is a
 * category search - matching names only, and Favorites drops out. Returns indexes into [rows].
 */
export function filterChips(rows: CategoryRow[], query: string): number[] {
  const q = query.trim().toLowerCase()
  return rows.map((r, i) => [r, i] as const).filter(([r]) => !q || (r.id !== FAVOURITES_ID && r.name.toLowerCase().includes(q))).map(([, i]) => i)
}

/**
 * Every list by category and by id, built ONCE per catalogue (kept in the controller's memo). W4 QA
 * P1: picking a category filtered all 15,951 channels / 69,536 films on every focus move.
 */
export interface CatalogueIndex<T> { byCat: Map<string, T[]>; byId: Map<string, T> }
export function indexCatalogue<T extends { id: string; categoryId: string }>(items: T[]): CatalogueIndex<T> {
  const byCat = new Map<string, T[]>()
  const byId = new Map<string, T>()
  for (const it of items) {
    byId.set(it.id, it)
    const list = byCat.get(it.categoryId)
    if (list) list.push(it); else byCat.set(it.categoryId, [it])
  }
  return { byCat, byId }
}

/** The channels of one category, in the provider's order (session sorts them). Favourites keep the order they were added in. */
export function channelsOf(categoryId: string, streams: LiveStream[], favs: string[], index?: CatalogueIndex<LiveStream>): LiveStream[] {
  if (categoryId === ALL_ID) return streams
  if (categoryId === FAVOURITES_ID) {
    const byId = index?.byId ?? new Map(streams.map((s) => [s.id, s]))
    return favs.map((id) => byId.get(id)).filter((s): s is LiveStream => s !== undefined)
  }
  return index ? index.byCat.get(categoryId) ?? [] : streams.filter((s) => s.categoryId === categoryId)
}

/** Where the viewer starts: their favourites if they have any, else the provider's first category. */
export function startCategory(rows: CategoryRow[]): number {
  if (rows[0]?.count > 0) return 0
  return rows.length > 1 ? 1 : 0
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
