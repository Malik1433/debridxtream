import { describe, expect, it } from 'vitest'
import { decodeTitle, parseShortEpg } from './epg'
import { favourites, toggleFavourite } from './favourites'
import { parseCategories, parseStreams } from './xtreamApi'

const b64 = (s: string) => Buffer.from(s, 'utf8').toString('base64')

describe('EPG parsing', () => {
  it('decodes base64 titles (UTF-8), and leaves plain text alone', () => {
    expect(decodeTitle(b64('Champions League – Live'))).toBe('Champions League – Live')
    expect(decodeTitle('News at Ten')).toBe('News at Ten')
    expect(decodeTitle('')).toBe('')
  })
  it('keeps what is still to come, now first, from unix or text times', () => {
    const now = Date.parse('2026-10-01T20:30:00Z')
    const u = (iso: string) => String(Date.parse(iso) / 1000)
    const body = { epg_listings: [
      { title: b64('Next'), start_timestamp: u('2026-10-01T21:00:00Z'), stop_timestamp: u('2026-10-01T22:00:00Z') },
      { title: b64('Over'), start_timestamp: u('2026-10-01T19:00:00Z'), stop_timestamp: u('2026-10-01T20:00:00Z') },
      { title: 'Now', start: '2026-10-01T20:00:00Z', end: '2026-10-01T21:00:00Z' },
    ] }
    expect(parseShortEpg(body, now).map((p) => p.title)).toEqual(['Now', 'Next'])
    expect(parseShortEpg({}, now)).toEqual([])
  })
})

describe('catalogue order and favourites', () => {
  it('keeps the provider order, which IndexedDB would otherwise lose', () => {
    expect(parseStreams([{ stream_id: 9, name: 'b' }, { stream_id: 1, name: 'a' }]).map((s) => s.order)).toEqual([0, 1])
    expect(parseCategories([{ category_id: 5, category_name: 'x' }])[0].order).toBe(0)
  })
  it('favourites toggle, and live under the server-scoped prefix the purge clears', () => {
    const m = new Map<string, string>()
    const kv = { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => { m.set(k, v) } }
    expect(toggleFavourite(kv, '7')).toEqual(['7'])
    expect(toggleFavourite(kv, '8')).toEqual(['7', '8'])
    expect(toggleFavourite(kv, '7')).toEqual(['8'])
    expect([...m.keys()]).toEqual(['dx.srv.favourites'])
    expect(favourites(kv)).toEqual(['8'])
  })
})
