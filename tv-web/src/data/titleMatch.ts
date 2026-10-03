import { cleanTitle } from './titles'
import { normalizeTitle } from './tmdb'

/** The key a provider title is matched on: cleaned, year dropped, letters and digits only. */
export const titleKey = (name: string) => normalizeTitle(cleanTitle(name).replace(/\(\d{4}\)/, ''))

/**
 * The first provider item for each wanted title (TMDB recommendations -> SIMILAR MOVIES).
 * W4 QA round 8: building a key for all 69,500 films (`memo:movie-title-index`) cost 1656 ms on the
 * Samsung, to look up about a dozen titles. Now each name is only stripped to letters and digits
 * and checked for a wanted key; the full clean-up runs on the few that contain one. Same answer
 * whenever the wanted title appears in the raw name - every case but an HTML escape inside it.
 */
export function matchTitles<T extends { name: string }>(items: readonly T[], titles: readonly string[]): Map<string, T> {
  const wanted = new Set(titles.map((t) => normalizeTitle(t)).filter((k): k is string => Boolean(k)))
  const found = new Map<string, T>()
  if (!wanted.size) return found
  const keys = [...wanted]
  for (const x of items) {
    if (found.size === wanted.size) break
    const raw = x.name.toLowerCase().replace(/[^a-z0-9]/g, '')
    if (!keys.some((k) => raw.includes(k))) continue
    const k = titleKey(x.name)
    if (k && wanted.has(k) && !found.has(k)) found.set(k, x)
  }
  return found
}
