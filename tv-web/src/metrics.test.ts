import { describe, expect, it } from 'vitest'
import { PlayMetrics } from './metrics'
import { apiUrl, liveUrl, redact } from './xtream'

describe('PlayMetrics', () => {
  it('counts the start as start, and a later buffering as a stop with its length', () => {
    let t = 0
    const m = new PlayMetrics(() => t)
    m.load(); m.buffering(); t = 1500; m.playing()
    expect(m.ttffMs).toBe(1500)
    expect(m.stalls).toBe(0)
    t = 10_000; m.buffering(); m.buffering(); t = 12_500; m.playing()
    expect(m.stalls).toBe(1)
    expect(m.stalledMs).toBe(2500)
    t = 20_000
    expect(m.playedMs).toBe(20_000 - 1500 - 2500)
  })
})

describe('xtream urls', () => {
  const c = { server: 'host.example:8080/', username: 'u s', password: 'p&w' }
  it('builds the API and live urls, escaping credentials', () => {
    expect(apiUrl(c, 'get_live_streams')).toBe(
      'http://host.example:8080/player_api.php?username=u%20s&password=p%26w&action=get_live_streams')
    expect(liveUrl(c, '42')).toBe('http://host.example:8080/live/u%20s/p%26w/42.ts')
  })
  it('never lets the host or a credential reach the screen', () => {
    expect(redact('GET http://host.example:8080/live/u s/p&w/1.ts', c)).toBe('GET ***/live/***/***/1.ts')
  })
})
