import type { Platform } from '../platform'

export type PlayerState = 'idle' | 'buffering' | 'ready' | 'ended'
export interface PlayerError { http?: number; network: boolean; message: string }

/** One player per TV behind one interface (design §4). W1 has the live `.ts` path only. */
export interface PlayerAdapter {
  readonly name: string
  /** Stops whatever played before, then opens [url]: one provider connection at a time. */
  load(url: string, opts: { live: boolean }): void
  play(): void
  pause(): void
  stop(): void
  onState(cb: (s: PlayerState) => void): void
  onError(cb: (e: PlayerError) => void): void
  bufferedAheadMs(): number | null
  /** Slow-fill (0.97x); false when this player cannot change speed. */
  setSpeed(rate: number): boolean
  destroy(): void
}

/**
 * Which player a TV gets: mpegts.js, on every TV, and the Samsung W0 said so.
 *
 * The reason AVPlay was the Samsung candidate was overlay - a player that draws under the page.
 * It turned out mpegts.js takes HTML over it on Samsung too (unlike VIDAA's own <video>, which
 * takes none), so that reason never existed. Measured on the same channel, same TV (2026-09-30):
 *
 *   AVPlay     first picture 0.4 s · played  13 s · 4 stops (94.8 s) · buffer ahead n/a
 *   mpegts.js  first picture 0.6 s · played 664 s · 1 stop   (0.1 s) · buffer ahead 8.5 s
 *
 * `buffer ahead` is the deciding column, not the stops: LiveCushionPolicy, slow-fill and hold-on
 * are all written against a cushion in milliseconds, and AVPlay reports none.
 *
 * This is not "AVPlay cannot work" - it ran on its defaults, with no live `.ts` buffering params
 * set. AvplayAdapter stays for the day hardware decode or HEVC is worth that work.
 */
export function playerKindFor(_platform: Platform): 'avplay' | 'mpegts' {
  return 'mpegts'
}
