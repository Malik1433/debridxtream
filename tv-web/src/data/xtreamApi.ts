import { apiUrl, type W0Config } from '../xtream'

/** The account this TV plays from - the same shape the W0 config used. */
export type XtreamAccount = W0Config

/** `order` keeps the provider's own order: IndexedDB hands rows back sorted by id, not as served. */
export interface LiveCategory { id: string; name: string; order?: number }
export interface LiveStream { id: string; name: string; categoryId: string; icon: string; epgId: string; archive: boolean; order?: number }
export interface AccountInfo { status: string; maxConnections: number; expiresAt: number | null; timezone: string | null }

/** Every network call is bounded (CLAUDE.md): a timeout degrades, it never hangs the screen. */
export async function fetchJson(url: string, timeoutMs: number, fetcher: typeof fetch = fetch): Promise<unknown> {
  const ctl = typeof AbortController !== 'undefined' ? new AbortController() : null
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => { ctl?.abort(); reject(new Error(`timeout after ${timeoutMs} ms`)) }, timeoutMs)
  })
  try {
    const res = await Promise.race([fetcher(url, ctl ? { signal: ctl.signal } : undefined), timeout])
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return await Promise.race([res.json(), timeout])
  } finally {
    clearTimeout(timer)
  }
}

const str = (v: unknown) => (v === null || v === undefined ? '' : String(v))

export function parseAccount(body: unknown): AccountInfo {
  const b = body as { user_info?: Record<string, unknown>; server_info?: Record<string, unknown> } | null
  const u = b?.user_info
  if (!u || u.auth === 0 || u.auth === '0') throw new Error('login refused')
  const exp = Number(u.exp_date)
  return {
    status: str(u.status) || 'Active',
    maxConnections: Number(u.max_connections) || 1,
    expiresAt: Number.isFinite(exp) && exp > 0 ? exp * 1000 : null,
    timezone: str(b?.server_info?.timezone) || null,
  }
}

export function parseCategories(body: unknown): LiveCategory[] {
  if (!Array.isArray(body)) return []
  return body.map((c, i) => ({ id: str(c?.category_id), name: str(c?.category_name), order: i })).filter((c) => c.id && c.name)
}

/** Only what the Live screen needs: 17,000+ channels must stay small in IndexedDB and in memory. */
export function parseStreams(body: unknown): LiveStream[] {
  if (!Array.isArray(body)) return []
  const out: LiveStream[] = []
  for (const s of body) {
    const id = str(s?.stream_id)
    if (!id) continue
    out.push({
      id,
      name: str(s?.name).trim() || `#${id}`,
      categoryId: str(s?.category_id),
      icon: str(s?.stream_icon),
      epgId: str(s?.epg_channel_id),
      archive: s?.tv_archive === 1 || s?.tv_archive === '1',
      order: out.length,
    })
  }
  return out
}

export const LOGIN_TIMEOUT_MS = 15_000
export const LIST_TIMEOUT_MS = 60_000

export async function login(a: XtreamAccount, fetcher?: typeof fetch): Promise<AccountInfo> {
  return parseAccount(await fetchJson(apiUrl(a), LOGIN_TIMEOUT_MS, fetcher))
}

export async function liveCatalogue(a: XtreamAccount, fetcher?: typeof fetch): Promise<{ categories: LiveCategory[]; streams: LiveStream[] }> {
  const [cats, streams] = await Promise.all([
    fetchJson(apiUrl(a, 'get_live_categories'), LIST_TIMEOUT_MS, fetcher),
    fetchJson(apiUrl(a, 'get_live_streams'), LIST_TIMEOUT_MS, fetcher),
  ])
  return { categories: parseCategories(cats), streams: parseStreams(streams) }
}
