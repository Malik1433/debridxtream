import { apiUrl, baseUrl } from '../xtream'
import { fetchJson, parseCategories, type LiveCategory, type XtreamAccount } from './xtreamApi'

/** Movies and series categories have the same shape as Live ones. */
export type VodCategory = LiveCategory

/** One movie in a list. Only what a poster grid needs: 50,000 of these live in IndexedDB. */
export interface Movie { id: string; name: string; categoryId: string; poster: string; rating: number; added: number; ext: string; order?: number }
/** One series in a list. */
export interface Show { id: string; name: string; categoryId: string; poster: string; rating: number; added: number; year: string; order?: number }

export interface MovieInfo {
  plot: string; cast: string; director: string; genre: string; year: string; durationSecs: number
  backdrop: string; poster: string; rating: number; ext: string
}
export interface Episode { id: string; season: number; number: number; title: string; plot: string; durationSecs: number; still: string; ext: string }
export interface ShowInfo { plot: string; cast: string; genre: string; year: string; backdrop: string; poster: string; rating: number; seasons: number[]; episodes: Episode[] }

const str = (v: unknown) => (v === null || v === undefined ? '' : String(v))
const num = (v: unknown) => { const n = Number(v); return Number.isFinite(n) ? n : 0 }
/** Providers send a backdrop as a string, an array of strings, or nothing. */
const firstUrl = (v: unknown): string => (Array.isArray(v) ? str(v.find((x) => typeof x === 'string' && x)) : str(v))
const yearOf = (d: unknown): string => { const m = /(\d{4})/.exec(str(d)); return m ? m[1] : '' }

export function parseMovies(body: unknown): Movie[] {
  if (!Array.isArray(body)) return []
  const out: Movie[] = []
  for (const s of body) {
    const id = str(s?.stream_id)
    if (!id) continue
    out.push({
      id, name: str(s?.name).trim() || `#${id}`, categoryId: str(s?.category_id), poster: str(s?.stream_icon),
      rating: num(s?.rating_5based) || num(s?.rating) / 2, added: num(s?.added) * 1000, ext: str(s?.container_extension) || 'mp4',
      order: out.length,
    })
  }
  return out
}

export function parseShows(body: unknown): Show[] {
  if (!Array.isArray(body)) return []
  const out: Show[] = []
  for (const s of body) {
    const id = str(s?.series_id)
    if (!id) continue
    out.push({
      id, name: str(s?.name).trim() || `#${id}`, categoryId: str(s?.category_id), poster: str(s?.cover),
      rating: num(s?.rating_5based) || num(s?.rating) / 2, added: num(s?.last_modified) * 1000,
      year: yearOf(s?.releaseDate ?? s?.release_date), order: out.length,
    })
  }
  return out
}

export function parseMovieInfo(body: unknown, fallbackExt = 'mp4'): MovieInfo {
  const b = (body ?? {}) as { info?: Record<string, unknown>; movie_data?: Record<string, unknown> }
  const i = (b.info && typeof b.info === 'object' && !Array.isArray(b.info) ? b.info : {}) as Record<string, unknown>
  const d = (b.movie_data ?? {}) as Record<string, unknown>
  return {
    plot: str(i.plot ?? i.description).trim(), cast: str(i.cast ?? i.actors).trim(), director: str(i.director).trim(),
    genre: str(i.genre).trim(), year: yearOf(i.releasedate ?? i.release_date), durationSecs: num(i.duration_secs),
    backdrop: firstUrl(i.backdrop_path), poster: str(i.movie_image ?? i.cover_big), rating: num(i.rating) / 2,
    ext: str(d.container_extension) || fallbackExt,
  }
}

/**
 * `episodes` arrives as {"1": [...], "2": [...]}, as a flat array, or as []/false/"" when there are
 * none (Android XtreamResponseParser handles the same three). Never throws.
 */
export function parseEpisodes(raw: unknown): Episode[] {
  const list: Array<{ season: string; e: Record<string, unknown> }> = []
  if (Array.isArray(raw)) raw.forEach((e) => { if (e && typeof e === 'object') list.push({ season: str(e.season) || '1', e }) })
  else if (raw && typeof raw === 'object') {
    for (const [season, eps] of Object.entries(raw as Record<string, unknown>)) {
      if (Array.isArray(eps)) eps.forEach((e) => { if (e && typeof e === 'object') list.push({ season, e }) })
    }
  }
  const out: Episode[] = []
  for (const { season, e } of list) {
    const id = str(e.id)
    if (!id) continue
    const info = (e.info && typeof e.info === 'object' ? e.info : {}) as Record<string, unknown>
    const n = num(e.episode_num)
    out.push({
      id, season: num(e.season) || num(season) || 1, number: n,
      title: str(e.title).trim() || `Episode ${n || out.length + 1}`, plot: str(info.plot).trim(),
      durationSecs: num(info.duration_secs), still: str(info.movie_image), ext: str(e.container_extension) || 'mp4',
    })
  }
  return out.sort((a, b) => a.season - b.season || a.number - b.number)
}

export function parseShowInfo(body: unknown): ShowInfo {
  const b = (body ?? {}) as { info?: Record<string, unknown>; episodes?: unknown }
  const i = (b.info && typeof b.info === 'object' ? b.info : {}) as Record<string, unknown>
  const episodes = parseEpisodes(b.episodes)
  return {
    plot: str(i.plot).trim(), cast: str(i.cast).trim(), genre: str(i.genre).trim(), year: yearOf(i.releaseDate ?? i.release_date),
    backdrop: firstUrl(i.backdrop_path), poster: str(i.cover), rating: num(i.rating_5based) || num(i.rating) / 2,
    seasons: [...new Set(episodes.map((e) => e.season))], episodes,
  }
}

export const INFO_TIMEOUT_MS = 15_000
export const LIBRARY_TIMEOUT_MS = 90_000

export async function movieLibrary(a: XtreamAccount, fetcher?: typeof fetch): Promise<{ categories: VodCategory[]; items: Movie[] }> {
  const [c, s] = await Promise.all([
    fetchJson(apiUrl(a, 'get_vod_categories'), LIBRARY_TIMEOUT_MS, fetcher),
    fetchJson(apiUrl(a, 'get_vod_streams'), LIBRARY_TIMEOUT_MS, fetcher),
  ])
  return { categories: parseCategories(c), items: parseMovies(s) }
}

export async function showLibrary(a: XtreamAccount, fetcher?: typeof fetch): Promise<{ categories: VodCategory[]; items: Show[] }> {
  const [c, s] = await Promise.all([
    fetchJson(apiUrl(a, 'get_series_categories'), LIBRARY_TIMEOUT_MS, fetcher),
    fetchJson(apiUrl(a, 'get_series'), LIBRARY_TIMEOUT_MS, fetcher),
  ])
  return { categories: parseCategories(c), items: parseShows(s) }
}

export async function movieInfo(a: XtreamAccount, id: string, ext: string, fetcher?: typeof fetch): Promise<MovieInfo> {
  return parseMovieInfo(await fetchJson(`${apiUrl(a, 'get_vod_info')}&vod_id=${encodeURIComponent(id)}`, INFO_TIMEOUT_MS, fetcher), ext)
}

export async function showInfo(a: XtreamAccount, id: string, fetcher?: typeof fetch): Promise<ShowInfo> {
  return parseShowInfo(await fetchJson(`${apiUrl(a, 'get_series_info')}&series_id=${encodeURIComponent(id)}`, INFO_TIMEOUT_MS, fetcher))
}

/** Built from the session every time: a stream URL carries the credentials and is never stored. */
export function movieUrl(a: XtreamAccount, id: string, ext: string): string {
  return `${baseUrl(a.server)}/movie/${encodeURIComponent(a.username)}/${encodeURIComponent(a.password)}/${id}.${ext || 'mp4'}`
}

export function episodeUrl(a: XtreamAccount, id: string, ext: string): string {
  return `${baseUrl(a.server)}/series/${encodeURIComponent(a.username)}/${encodeURIComponent(a.password)}/${id}.${ext || 'mp4'}`
}
