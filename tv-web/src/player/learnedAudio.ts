/**
 * Audio codecs this TV has shown it cannot play through mpegts.js, whatever MediaSource.isTypeSupported
 * claims. W3d QA (2026-10-02): the 4K/MP3 channel waited the full 25 s connect timeout before moving
 * to AVPlay - the TV answers "yes" to `audio/mpeg` and then plays nothing. A promise from the TV is
 * not proof; a channel that failed is. Device-level (not server-scoped): it is about this TV's
 * decoder, not about the provider.
 */
const KEY = 'dx.mseBadAudio'
type Kv = Pick<Storage, 'getItem' | 'setItem'>

const norm = (codec: string) => codec.trim().toLowerCase()

export function learnedBad(kv: Kv): Set<string> {
  try { const v = JSON.parse(kv.getItem(KEY) ?? '[]'); return new Set(Array.isArray(v) ? v.map((x) => norm(String(x))) : []) } catch { return new Set() }
}

/** AAC is never learned as bad: it is the codec MSE exists for, and a dead AAC channel is the provider's fault. */
export function learnBad(kv: Kv, codec: string | undefined): boolean {
  if (!codec || isAac(codec)) return false
  const s = learnedBad(kv)
  if (s.has(norm(codec))) return false
  s.add(norm(codec))
  try { kv.setItem(KEY, JSON.stringify([...s])) } catch { /* storage full: learn again next time */ }
  return true
}

export const isAac = (codec: string) => norm(codec).startsWith('mp4a.40')
