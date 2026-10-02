import { describe, expect, it } from 'vitest'
import type { LiveStream } from '../../../data/xtreamApi'
import { ALL_ID, FAVOURITES_ID, categoryRows, channelsOf, indexCatalogue, progress, startCategory, zapIndex } from './liveModel'

const s = (id: string, categoryId: string): LiveStream => ({ id, name: `ch${id}`, categoryId, icon: '', epgId: '', archive: false })
const streams = [s('1', 'a'), s('2', 'b'), s('3', 'a')]
const cats = [{ id: 'a', name: 'News' }, { id: 'b', name: 'Sport' }, { id: 'c', name: 'Empty' }]

describe('liveModel', () => {
  it('puts Favourites and All first and drops empty categories', () => {
    const rows = categoryRows(cats, streams, ['3'])
    expect(rows.map((r) => r.id)).toEqual([FAVOURITES_ID, ALL_ID, 'a', 'b'])
    expect(rows.map((r) => r.count)).toEqual([1, 3, 2, 1])
  })

  it('lists a category in provider order and favourites in the order added', () => {
    expect(channelsOf('a', streams, []).map((x) => x.id)).toEqual(['1', '3'])
    expect(channelsOf(FAVOURITES_ID, streams, ['3', 'gone', '1']).map((x) => x.id)).toEqual(['3', '1'])
    expect(channelsOf(ALL_ID, streams, []).length).toBe(3)
  })

  it('starts on favourites only when there are some', () => {
    expect(startCategory(categoryRows(cats, streams, ['1']))).toBe(0)
    expect(startCategory(categoryRows(cats, streams, []))).toBe(2)
    expect(startCategory(categoryRows([], [], []))).toBe(1)
  })

  it('zaps round both ends', () => {
    expect(zapIndex(0, -1, 5)).toBe(4)
    expect(zapIndex(4, 1, 5)).toBe(0)
    expect(zapIndex(2, 1, 5)).toBe(3)
    expect(zapIndex(0, 1, 0)).toBe(-1)
  })

  it('clamps the programme bar', () => {
    expect(progress(0, 100, 50)).toBe(0.5)
    expect(progress(0, 100, 500)).toBe(1)
    expect(progress(100, 100, 50)).toBe(0)
  })

  it('indexes by category and id once, and lists from the index', () => {
    const idx = indexCatalogue(streams)
    expect(channelsOf('a', streams, [], idx).map((x) => x.id)).toEqual(['1', '3'])
    expect(channelsOf('zz', streams, [], idx)).toEqual([])
    expect(channelsOf(FAVOURITES_ID, streams, ['3', 'gone'], idx).map((x) => x.id)).toEqual(['3'])
  })
})
