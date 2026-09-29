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
 * Which player a TV gets. W0 (VIDAA): the TV's own <video> shows no HTML over it, mpegts.js does,
 * so every web-video TV uses mpegts.js. Samsung's AVPlay draws under the page; it stays the Samsung
 * choice until the Samsung W0 says otherwise.
 */
export function playerKindFor(platform: Platform): 'avplay' | 'mpegts' {
  return platform === 'tizen' ? 'avplay' : 'mpegts'
}
