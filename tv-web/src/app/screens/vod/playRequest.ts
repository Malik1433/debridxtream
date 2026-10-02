import type { Episode } from '../../../data/vodApi'

/** What the player is asked to play. Ids only: the URL is built from the session when it opens. */
export interface PlayRequest {
  kind: 'movie' | 'episode'
  id: string
  ext: string
  title: string
  /** "S1 · E3" for an episode, the year for a film. */
  subtitle: string
  poster: string
  startMs: number
  seriesId?: string
  seriesName?: string
  season?: number
  episode?: number
  /** Episodes of the same show in order, so the player can offer the next one. */
  queue?: Episode[]
}

export function episodeRequest(e: Episode, show: { id: string; name: string; poster: string }, queue: Episode[], startMs: number): PlayRequest {
  return {
    kind: 'episode', id: e.id, ext: e.ext, title: e.title, subtitle: `S${e.season} · E${e.number}`, poster: e.still || show.poster,
    startMs, seriesId: show.id, seriesName: show.name, season: e.season, episode: e.number, queue,
  }
}

/** The episode after [r] in its queue, or null at the end of the show. */
export function nextEpisode(r: PlayRequest): Episode | null {
  if (r.kind !== 'episode' || !r.queue) return null
  const i = r.queue.findIndex((e) => e.id === r.id)
  return i >= 0 && i + 1 < r.queue.length ? r.queue[i + 1] : null
}

/** The episode before [r], or null at the start of the show (PREV EP). */
export function prevEpisode(r: PlayRequest): Episode | null {
  if (r.kind !== 'episode' || !r.queue) return null
  const i = r.queue.findIndex((e) => e.id === r.id)
  return i > 0 ? r.queue[i - 1] : null
}

/** Show the "next episode" prompt in the last 15 s (the credits) - Android PlayerNextEpisodeManager. */
export const NEXT_PROMPT_MS = 15_000
export function showNextPrompt(posMs: number, durMs: number): boolean {
  return durMs > 60_000 && posMs > 0 && durMs - posMs <= NEXT_PROMPT_MS
}
