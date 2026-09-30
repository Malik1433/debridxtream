import { describe, expect, it } from 'vitest'
import { parsePlaylist, pickActive, type AccountPlaylist } from './playlists'

const p = (id: string, over: Partial<AccountPlaylist> = {}): AccountPlaylist => ({
  id, name: id, url: 'http://x', username: 'u', password: 'p', enabled: true, isXtream: true,
  createdAtMs: 0, assignedTo: '', ...over,
})

describe('pickActive (port of AccountPlaylistSync.applyActive)', () => {
  it('prefers the viewer\'s choice, then one addressed to this TV, then one for all', () => {
    const all = [p('all', { createdAtMs: 1 }), p('mine', { assignedTo: 'tv-1', createdAtMs: 2 }), p('picked', { createdAtMs: 3 })]
    expect(pickActive(all, 'tv-1', 'picked')?.id).toBe('picked')
    expect(pickActive(all, 'tv-1', null)?.id).toBe('mine')
    expect(pickActive(all, 'tv-2', null)?.id).toBe('all')
  })
  it('never runs another TV\'s playlist, a disabled one, or an M3U', () => {
    expect(pickActive([p('other', { assignedTo: 'tv-9' })], 'tv-1', 'other')).toBeNull()
    expect(pickActive([p('off', { enabled: false }), p('m3u', { isXtream: false })], 'tv-1', null)).toBeNull()
  })
  it('falls through when the chosen playlist was deleted, oldest first', () => {
    const all = [p('b', { createdAtMs: 5 }), p('a', { createdAtMs: 1 })]
    expect(pickActive(all, 'tv-1', 'gone')?.id).toBe('a')
  })
})

describe('parsePlaylist', () => {
  it('maps the phone\'s fields and names a nameless playlist by its host', () => {
    const x = parsePlaylist('id1', { url: 'http://tv.example:8080/', username: 'u', password: 'p', type: 'xtream', deviceId: 'tv-1',
      createdAt: { toMillis: () => 42 } })
    expect(x).toMatchObject({ name: 'tv.example', assignedTo: 'tv-1', isXtream: true, enabled: true, createdAtMs: 42 })
    expect(parsePlaylist('id2', { name: 'n' })).toBeNull()
    expect(parsePlaylist('id3', { url: 'http://a', type: 'm3u' })?.isXtream).toBe(false)
  })
})
