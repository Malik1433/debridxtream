import { describe, expect, it } from 'vitest'
import { appendChar, appendSpace, deleteChar, MAX_QUERY, scopedResults, yearOf } from './searchModel'
import type { Movie, Show } from '../../../data/vodApi'
import type { LiveStream } from '../../../data/xtreamApi'

const movie = (id: string, name: string, categoryId = '1') => ({ id, name, categoryId, poster: '', rating: 0, added: 0, ext: 'mp4' }) as Movie
const show = (id: string, name: string, categoryId = '2') => ({ id, name, categoryId, poster: '', rating: 0, added: 0, year: '' }) as Show
const chan = (id: string, name: string) => ({ id, name }) as LiveStream
const data = {
  movies: [movie('m1', 'Dune'), movie('m2', 'Dune Part Two', '9')],
  shows: [show('s1', 'Dune Prophecy')],
  live: [chan('l1', 'Dune TV')],
}

describe('searchModel', () => {
  it('edits the query like the Android key grid', () => {
    expect(appendSpace('')).toBe('')
    expect(appendSpace('A')).toBe('A ')
    expect(deleteChar('AB')).toBe('A')
    expect(deleteChar('')).toBe('')
    const full = 'X'.repeat(MAX_QUERY)
    expect(appendChar(full, 'Y')).toBe(full)
    expect(appendSpace(full)).toBe(full)
  })
  it('orders All as films, series, channels and needs two letters', () => {
    expect(scopedResults(data, 'D', 'all')).toEqual([])
    expect(scopedResults(data, 'DUNE', 'all').map((r) => r.kind)).toEqual(['movie', 'movie', 'series', 'live'])
    expect(scopedResults(data, 'dune', 'live').map((r) => r.item.id)).toEqual(['l1'])
  })
  it('narrows the opened kind to its category', () => {
    expect(scopedResults(data, 'dune', 'movie', '9').map((r) => r.item.id)).toEqual(['m2'])
  })
  it('reads a year only when there is one', () => {
    expect(yearOf('2021-10-22')).toBe('2021')
    expect(yearOf('n/a')).toBeNull()
    expect(yearOf(undefined)).toBeNull()
  })
})
