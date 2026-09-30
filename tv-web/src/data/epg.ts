import { apiUrl, type W0Config } from '../xtream'
import { fetchJson } from './xtreamApi'

export interface Programme { title: string; start: number; end: number }

/** Xtream sends titles base64-encoded (sometimes not). Decode as UTF-8, fall back to the raw text. */
export function decodeTitle(raw: unknown): string {
  const s = typeof raw === 'string' ? raw.trim() : ''
  if (!s || !/^[A-Za-z0-9+/=\s]+$/.test(s) || s.length % 4 !== 0) return s
  try {
    const bin = atob(s.replace(/\s/g, ''))
    const bytes = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    return /[\u0000-\u0008]/.test(text) ? s : text.trim()
  } catch {
    return s
  }
}

/** `get_short_epg` -> the programmes still to come, now first. Unix seconds or 'YYYY-MM-DD HH:MM:SS'. */
export function parseShortEpg(body: unknown, nowMs: number): Programme[] {
  const list = (body as { epg_listings?: unknown[] } | null)?.epg_listings
  if (!Array.isArray(list)) return []
  const time = (unix: unknown, text: unknown) => {
    const n = Number(unix)
    if (Number.isFinite(n) && n > 0) return n * 1000
    const t = Date.parse(String(text ?? '').replace(' ', 'T'))
    return Number.isFinite(t) ? t : 0
  }
  return list
    .map((p) => {
      const x = p as Record<string, unknown>
      return { title: decodeTitle(x.title), start: time(x.start_timestamp, x.start), end: time(x.stop_timestamp, x.end ?? x.stop) }
    })
    .filter((p) => p.title && p.end > nowMs)
    .sort((a, b) => a.start - b.start)
}

export const EPG_TIMEOUT_MS = 8_000

/** Now/next for one channel, cached for a minute; a failure is "no guide", never an error screen. */
export class EpgCache {
  private cache = new Map<string, { at: number; items: Programme[] }>()
  constructor(private readonly account: () => W0Config | null, private readonly fetcher?: typeof fetch) {}

  async nowNext(streamId: string): Promise<Programme[]> {
    const hit = this.cache.get(streamId)
    const now = Date.now()
    if (hit && now - hit.at < 60_000) return hit.items.filter((p) => p.end > now)
    const a = this.account()
    if (!a) return []
    try {
      const body = await fetchJson(`${apiUrl(a, 'get_short_epg')}&stream_id=${encodeURIComponent(streamId)}&limit=4`, EPG_TIMEOUT_MS, this.fetcher)
      const items = parseShortEpg(body, now).slice(0, 2)
      this.cache.set(streamId, { at: now, items })
      return items
    } catch {
      return []
    }
  }
}
