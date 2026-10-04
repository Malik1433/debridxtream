import type { VodCategory } from '../../../data/vodApi'
import type { CatalogueIndex } from '../live/liveModel'

export const FAV_ID = '__fav'
export const RECENT_ID = '__recent'
export const ALL_ID = '__all'
export const RECENT_LIMIT = 300

export interface LibRow { id: string; name: string; count: number }
type Item = { id: string; categoryId: string; added: number; name: string; rating: number; year?: string }

/**
 * Android VodFragment.buildSidebarCategories: All · Recently Added · Favorites, then the provider's
 * categories (empty ones left out - a dead end for the remote).
 */
export function libraryRows(cats: VodCategory[], items: Item[], favs: string[], allLabel = 'All Movies'): LibRow[] {
  const counts = new Map<string, number>()
  for (const i of items) counts.set(i.categoryId, (counts.get(i.categoryId) ?? 0) + 1)
  const fav = new Set(favs)
  return [
    { id: ALL_ID, name: allLabel, count: items.length },
    { id: RECENT_ID, name: 'Recently Added', count: Math.min(RECENT_LIMIT, items.length) },
    { id: FAV_ID, name: 'Favorites', count: items.filter((i) => fav.has(i.id)).length },
    ...cats.filter((c) => (counts.get(c.id) ?? 0) > 0).map((c) => ({ id: c.id, name: c.name, count: counts.get(c.id) ?? 0 })),
  ]
}

export function itemsOf<T extends Item>(rowId: string, items: T[], favs: string[], index?: CatalogueIndex<T>): T[] {
  if (rowId === ALL_ID) return items
  if (rowId === RECENT_ID) return [...items].sort((a, b) => b.added - a.added).slice(0, RECENT_LIMIT)
  if (rowId === FAV_ID) {
    const byId = index?.byId ?? new Map(items.map((i) => [i.id, i]))
    return favs.map((id) => byId.get(id)).filter((i): i is T => i !== undefined)
  }
  return index ? index.byCat.get(rowId) ?? [] : items.filter((i) => i.categoryId === rowId)
}

/** Android opens on the first row: All. */
export function startRow(_rows: LibRow[]): number { return 0 }

/** Android VodFragment.SortMode, in the chip order. */
export type SortMode = 'recent' | 'rated' | 'az' | 'newest'
export const SORTS: Array<{ mode: SortMode; label: string }> = [
  { mode: 'recent', label: 'RECENTLY ADDED' }, { mode: 'rated', label: 'TOP RATED' }, { mode: 'az', label: 'A – Z' }, { mode: 'newest', label: 'NEWEST' },
]

const yearOf = (i: Item): number => Number(i.year) || Number(/\((\d{4})\)/.exec(i.name)?.[1]) || 0

/**
 * The numeric keys behind a sort mode, for sorting off the main thread (round 10); null for A - Z,
 * which compares strings and stays where it is. Same order as sortItems: see offThreadSort.orderBy.
 */
export function numericSortKeys(items: readonly Item[], mode: SortMode): { primary: Float64Array; secondary: Float64Array | null } | null {
  if (mode === 'az') return null
  const n = items.length
  const primary = new Float64Array(n)
  const secondary = mode === 'newest' ? new Float64Array(n) : null
  for (let i = 0; i < n; i++) {
    const it = items[i]
    primary[i] = mode === 'recent' ? it.added : mode === 'rated' ? it.rating : yearOf(it)
    if (secondary) secondary[i] = it.added
  }
  return { primary, secondary }
}

export function sortItems<T extends Item>(items: T[], mode: SortMode): T[] {
  const a = [...items]
  if (mode === 'recent') return a.sort((x, y) => y.added - x.added)
  if (mode === 'rated') return a.sort((x, y) => y.rating - x.rating)
  if (mode === 'az') return a.sort((x, y) => x.name.localeCompare(y.name))
  return a.sort((x, y) => yearOf(y) - yearOf(x) || y.added - x.added)
}
