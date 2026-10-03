import { searchByName } from '../../../data/search'
import type { Movie, Show } from '../../../data/vodApi'
import type { LiveStream } from '../../../data/xtreamApi'

/** Android SearchFragment: the on-screen key grid, six to a row, and the 32-character cap. */
export const KEYS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'.split('')
export const KEY_COLUMNS = 6
export const MAX_QUERY = 32
export type Scope = 'all' | 'movie' | 'series' | 'live'
export const SCOPES: Array<{ key: Scope; label: string }> = [
  { key: 'all', label: 'All' }, { key: 'movie', label: 'Movies' }, { key: 'series', label: 'Series' }, { key: 'live', label: 'Live' },
]
export const TRENDING = ['News', 'Sports', 'Action', 'Kids', 'Movies 4K', 'Documentary']
export const RESULT_LIMIT = 60

/** Opened from Movies or Series: that scope, and the open provider category (null = all of them). */
export interface SearchContext { scope: Scope; categoryId: string | null; categoryName: string | null }

export const appendChar = (q: string, c: string) => (q.length < MAX_QUERY ? q + c : q)
/** SPACE never leads and never passes the cap (Android onSpace). */
export const appendSpace = (q: string) => (q.length < MAX_QUERY && q.length > 0 ? q + ' ' : q)
export const deleteChar = (q: string) => q.slice(0, -1)

export type Result =
  | { kind: 'movie'; item: Movie }
  | { kind: 'series'; item: Show }
  | { kind: 'live'; item: LiveStream }

export interface Catalogue { live: LiveStream[]; movies: Movie[]; shows: Show[] }

/**
 * Android scopedResults: films, then series, then channels for All, capped at 60. A Movies/Series
 * context narrows its own kind to the category it was opened from.
 */
export function scopedResults(data: Catalogue, query: string, scope: Scope, categoryId: string | null = null): Result[] {
  const q = query.trim()
  if (q.length < 2) return []
  const inCat = <T extends { categoryId: string }>(xs: T[]) => (categoryId ? xs.filter((x) => x.categoryId === categoryId) : xs)
  const movies = () => searchByName(inCat(data.movies), q, RESULT_LIMIT).map((item): Result => ({ kind: 'movie', item }))
  const shows = () => searchByName(inCat(data.shows), q, RESULT_LIMIT).map((item): Result => ({ kind: 'series', item }))
  const live = () => searchByName(data.live, q, RESULT_LIMIT).map((item): Result => ({ kind: 'live', item }))
  switch (scope) {
    case 'movie': return movies()
    case 'series': return shows()
    case 'live': return live()
    default: return [...movies(), ...shows(), ...live()].slice(0, RESULT_LIMIT)
  }
}

/** The four-digit year from a release string, if it has one (Android year()). */
export function yearOf(s: string | undefined): string | null {
  const y = (s ?? '').slice(0, 4)
  return /^\d{4}$/.test(y) ? y : null
}
