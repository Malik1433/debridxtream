import { fetchJson } from './xtreamApi'

/**
 * TMDB enrichment - a port of the Android app's MovieDetailViewModelV2 / SeriesDetailEnrichment, so a
 * detail page looks the same on the TV as on the phone (owner rule: tv-web is a COPY of Android).
 * W4 QA N2: a film whose provider sends no backdrop_path showed a bare page; Android fills it (and
 * plot, genre, rating, director, cast) from TMDB.
 *
 * The key comes from the build (VITE_TMDB_API_KEY in tv-web/.env.local, gitignored) exactly as
 * Android's comes from local.properties. No key = no enrichment, and the page still works.
 * Bounded: 12 s for the whole lookup (Android B-11), a timeout just skips enrichment.
 */
const API = 'https://api.themoviedb.org/3'
const IMG = 'https://image.tmdb.org/t/p'
export const TMDB_BUDGET_MS = 12_000
const STEP_MS = 6_000

export interface Enrichment {
  backdrop: string; poster: string; plot: string; year: string; genre: string
  /** 0-5, like the provider's rating_5based. */
  rating: number; director: string; cast: string; runtimeMin: number
}

export function tmdbKey(): string {
  try { return ((import.meta as unknown as { env?: Record<string, string | undefined> }).env?.VITE_TMDB_API_KEY ?? '').trim() } catch { return '' }
}

/** Android normalizeTitle. */
export function normalizeTitle(v: string | null | undefined): string | null {
  const s = (v ?? '').toLowerCase().replace(/[^a-z0-9]/g, '')
  return s || null
}

/** Android buildSearchQueries: IPTV prefixes, release tokens and a trailing year come off, in that order. */
export function buildSearchQueries(title: string): string[] {
  const base = title.trim()
  const noPrefix = base
    .replace(/^(\s*\|[^|]{1,10}\|\s*)+/, '')
    .replace(/^\[[A-Za-z]{2,3}]\s*/, '')
    .replace(/^[A-Za-z]{2,3}\s*\|\s*/, '')
    .replace(/^[A-Za-z]{2,3}\s*-\s*/, '')
    .replace(/^\|?\s*MULTI\s*\|?\s*/i, '')
    .trim()
  const stripped = noPrefix
    .replace(/\b(multi|dual|audio|dubbed|hindi|english|french|spanish|arabic|turkish|portuguese)\b/gi, '')
    .replace(/\b(4k|2160p|1080p|720p|480p|hdr|dv|hevc|x265|x264|h\.265|h\.264|web[- ]?dl|webrip|bluray|brrip|dvdrip|cam|ts|tc|aac|dts|atmos)\b/gi, '')
    .replace(/\s+/g, ' ').trim()
  const noYear = stripped.replace(/\(\s*\d{4}\s*\)$/, '').replace(/\s+\d{4}$/, '').trim()
  const cleaned = noYear.replace(/[^\p{L}\p{N} ]/gu, ' ').replace(/\s+/g, ' ').trim()
  const firstWords = cleaned.split(' ').slice(0, 6).join(' ').trim()
  const out = [
    cleaned,
    firstWords && firstWords.length < cleaned.length ? firstWords : '',
    noYear !== cleaned ? noYear : '',
    stripped !== cleaned ? stripped : '',
    noPrefix !== cleaned ? noPrefix : '',
  ].filter(Boolean)
  return [...new Set(out)]
}

interface Candidate { id?: number; title?: string; name?: string; original_title?: string; release_date?: string; first_air_date?: string }

/** Android bestCandidateId: exact normalised title first, then the nearest year; no year scores worst. */
export function bestCandidateId(results: Candidate[], normalizedTarget: string | null, targetYear: number | null): number | null {
  let best: [number, number, number] | null = null
  for (const m of results) {
    if (typeof m.id !== 'number') continue
    const y = Number((m.release_date ?? m.first_air_date ?? '').slice(0, 4)) || null
    const yearScore = targetYear !== null && y !== null ? Math.abs(targetYear - y) : 99
    const titleScore = normalizedTarget !== null && normalizeTitle(m.title ?? m.name ?? m.original_title) === normalizedTarget ? 0 : 1
    if (!best || titleScore < best[0] || (titleScore === best[0] && yearScore < best[1])) best = [titleScore, yearScore, m.id]
  }
  return best ? best[2] : null
}

type Details = {
  backdrop_path?: string | null; poster_path?: string | null; overview?: string; release_date?: string; first_air_date?: string
  genres?: Array<{ name?: string }>; vote_average?: number; runtime?: number; episode_run_time?: number[]
  credits?: { cast?: Array<{ name?: string }>; crew?: Array<{ job?: string; name?: string }> }; created_by?: Array<{ name?: string }>
}

export function toEnrichment(d: Details): Enrichment {
  return {
    backdrop: d.backdrop_path ? `${IMG}/w1280${d.backdrop_path}` : '',
    poster: d.poster_path ? `${IMG}/w500${d.poster_path}` : '',
    plot: (d.overview ?? '').trim(),
    year: (d.release_date ?? d.first_air_date ?? '').slice(0, 4),
    genre: (d.genres ?? []).map((g) => g.name ?? '').filter(Boolean).join(', '),
    rating: typeof d.vote_average === 'number' ? Math.round(d.vote_average * 5) / 10 : 0,
    director: d.credits?.crew?.find((c) => (c.job ?? '').toLowerCase() === 'director')?.name ?? d.created_by?.[0]?.name ?? '',
    cast: (d.credits?.cast ?? []).slice(0, 5).map((c) => c.name ?? '').filter(Boolean).join(', '),
    runtimeMin: d.runtime ?? d.episode_run_time?.[0] ?? 0,
  }
}

const cache = new Map<string, Promise<Enrichment | null>>()

async function lookup(kind: 'movie' | 'tv', title: string, year: string, key: string, fetcher?: typeof fetch): Promise<Enrichment | null> {
  const targetYear = Number(year.slice(0, 4)) || null
  const queries = kind === 'movie' ? buildSearchQueries(title).slice(0, 4) : [buildSearchQueries(title)[0] ?? title]
  for (const q of queries) {
    const res = await fetchJson(`${API}/search/${kind}?api_key=${encodeURIComponent(key)}&query=${encodeURIComponent(q)}`, STEP_MS, fetcher)
      .catch(() => null) as { results?: Candidate[] } | null
    const results = res?.results ?? []
    if (!results.length) continue
    const id = bestCandidateId(results, normalizeTitle(q), targetYear)
    if (id === null) continue
    const d = await fetchJson(`${API}/${kind}/${id}?api_key=${encodeURIComponent(key)}&append_to_response=credits`, STEP_MS, fetcher).catch(() => null) as Details | null
    if (d) return toEnrichment(d)
  }
  return null
}

/** One lookup per title per run; null when there is no key, no match, or TMDB is slow. */
export function enrich(kind: 'movie' | 'tv', title: string, year: string, fetcher?: typeof fetch, key = tmdbKey()): Promise<Enrichment | null> {
  if (!key || !title.trim()) return Promise.resolve(null)
  const k = `${kind}|${title}|${year}`
  const hit = cache.get(k)
  if (hit) return hit
  let timer: ReturnType<typeof setTimeout> | undefined
  const budget = new Promise<null>((resolve) => { timer = setTimeout(() => resolve(null), TMDB_BUDGET_MS) })
  const p = Promise.race([lookup(kind, title, year, key, fetcher).catch(() => null), budget]).finally(() => clearTimeout(timer))
  cache.set(k, p)
  return p
}
