import type { VodCategory } from '../../../data/vodApi'

export const FAV_ID = '__fav'
export const RECENT_ID = '__recent'
export const ALL_ID = '__all'
export const RECENT_LIMIT = 300

export interface LibRow { id: string; name: string; count: number }
type Item = { id: string; categoryId: string; added: number }

/** Favourites, Recently added and All first; then the provider's categories, empty ones left out. */
export function libraryRows(cats: VodCategory[], items: Item[], favs: string[]): LibRow[] {
  const counts = new Map<string, number>()
  for (const i of items) counts.set(i.categoryId, (counts.get(i.categoryId) ?? 0) + 1)
  const fav = new Set(favs)
  return [
    { id: FAV_ID, name: 'Favourites', count: items.filter((i) => fav.has(i.id)).length },
    { id: RECENT_ID, name: 'Recently added', count: Math.min(RECENT_LIMIT, items.length) },
    { id: ALL_ID, name: 'All', count: items.length },
    ...cats.filter((c) => (counts.get(c.id) ?? 0) > 0).map((c) => ({ id: c.id, name: c.name, count: counts.get(c.id) ?? 0 })),
  ]
}

export function itemsOf<T extends Item>(rowId: string, items: T[], favs: string[]): T[] {
  if (rowId === ALL_ID) return items
  if (rowId === RECENT_ID) return [...items].sort((a, b) => b.added - a.added).slice(0, RECENT_LIMIT)
  if (rowId === FAV_ID) {
    const byId = new Map(items.map((i) => [i.id, i]))
    return favs.map((id) => byId.get(id)).filter((i): i is T => i !== undefined)
  }
  return items.filter((i) => i.categoryId === rowId)
}

/** Start on Favourites when there are some, else Recently added - what a returning viewer looks for. */
export function startRow(rows: LibRow[]): number { return rows[0]?.count > 0 ? 0 : rows.length > 1 ? 1 : 0 }
