import { describe, expect, it } from 'vitest'
import { episodeRequest, nextEpisode, showNextPrompt } from './playRequest'

const ep = (id: string, season: number, number: number) => ({ id, season, number, title: `E${number}`, plot: '', durationSecs: 0, still: '', ext: 'mkv' })
describe('playRequest', () => {
  const q = [ep('a', 1, 1), ep('b', 1, 2), ep('c', 2, 1)]
  it('walks the queue across seasons and stops at the end', () => {
    const r = episodeRequest(q[1], { id: 's', name: 'Show', poster: 'p' }, q, 0)
    expect(r).toMatchObject({ subtitle: 'S1 · E2', seriesId: 's', poster: 'p' })
    expect(nextEpisode(r)?.id).toBe('c')
    expect(nextEpisode(episodeRequest(q[2], { id: 's', name: 'Show', poster: '' }, q, 0))).toBeNull()
  })
  it('prompts only in the last 15 s of a real episode', () => {
    expect(showNextPrompt(2_390_000, 2_400_000)).toBe(true)
    expect(showNextPrompt(2_000_000, 2_400_000)).toBe(false)
    expect(showNextPrompt(40_000, 50_000)).toBe(false)
  })
})
