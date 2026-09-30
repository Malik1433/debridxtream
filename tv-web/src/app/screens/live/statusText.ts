import type { LiveStatus } from '../../../player/liveEngine'

/** What the pill over the picture says. null = the picture speaks for itself. */
export function statusText(s: LiveStatus): string | null {
  switch (s.kind) {
    case 'connecting': return s.slow ? 'The server is slow — still trying…' : `Connecting to ${s.channel.name}…`
    case 'buffering': return s.waitingForMs > 0 ? `Building a buffer (${Math.round(s.waitingForMs / 1000)} s)…` : 'Buffering…'
    case 'reconnecting': return `Reconnecting in ${Math.max(1, Math.round(s.inMs / 1000))} s…`
    case 'failed': return `This channel is not available (${s.message})`
    default: return null
  }
}
