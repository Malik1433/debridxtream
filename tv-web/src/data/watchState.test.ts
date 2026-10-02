import { describe, expect, it } from 'vitest'
import { continueWatching, forget, markWatched, recordProgress, resumePointMs, watchEntry, watchedEpisodes } from './watchState'

class Kv { m = new Map<string, string>(); getItem(k: string) { return this.m.get(k) ?? null } setItem(k: string, v: string) { this.m.set(k, v) } }
const movie = (id: string, progressMs: number, durationMs = 6_000_000) => ({ kind: 'movie' as const, id, title: id, poster: '', ext: 'mp4', progressMs, durationMs })

describe('watchState', () => {
  it('resumes only past 30 s and before 90 %', () => {
    const kv = new Kv()
    expect(resumePointMs(recordProgress(kv, movie('a', 10_000)))).toBe(0)
    expect(resumePointMs(recordProgress(kv, movie('a', 600_000)))).toBe(600_000)
    const done = recordProgress(kv, movie('a', 5_500_000))
    expect(done.watched).toBe(true)
    expect(resumePointMs(done)).toBe(0)
    expect(kv.m.has('dx.srv.watch')).toBe(true) // server-scoped: the purge takes it
  })

  it('lists continue watching newest first, one row per series', () => {
    const kv = new Kv()
    recordProgress(kv, movie('m1', 100_000), 1)
    recordProgress(kv, { ...movie('e1', 100_000), kind: 'episode', seriesId: 's', season: 1, episode: 1 }, 2)
    recordProgress(kv, { ...movie('e2', 200_000), kind: 'episode', seriesId: 's', season: 1, episode: 2 }, 3)
    recordProgress(kv, movie('m2', 5_900_000), 4) // finished
    expect(continueWatching(kv).map((e) => e.id)).toEqual(['e2', 'm1'])
    expect([...watchedEpisodes(kv, 's').keys()].sort()).toEqual(['e1', 'e2'])
    forget(kv, 'movie', 'm1')
    expect(watchEntry(kv, 'movie', 'm1')).toBeNull()
  })

  it('marks watched and unwatched by hand', () => {
    const kv = new Kv()
    markWatched(kv, { kind: 'movie', id: 'x', title: 'X', poster: '', ext: 'mp4' }, true)
    expect(watchEntry(kv, 'movie', 'x')?.watched).toBe(true)
    markWatched(kv, { kind: 'movie', id: 'x', title: 'X', poster: '', ext: 'mp4' }, false)
    expect(watchEntry(kv, 'movie', 'x')).toBeNull()
  })
})
