import { describe, expect, it } from 'vitest'
import { bestCandidateId, buildSearchQueries, enrich, normalizeTitle, toEnrichment } from './tmdb'

describe('tmdb (port of Android MovieDetailViewModelV2)', () => {
  it('cleans IPTV titles the way Android does', () => {
    expect(buildSearchQueries('|EN| Dune: Part Two (2024)')[0]).toBe('Dune Part Two')
    expect(buildSearchQueries('EN - The Batman 4K 2022')[0]).toBe('The Batman')
    expect(buildSearchQueries('[DE] Oppenheimer')[0]).toBe('Oppenheimer')
    expect(normalizeTitle('Dune: Part Two')).toBe('duneparttwo')
  })

  it('picks an exact title first, then the nearest year', () => {
    const r = [{ id: 1, title: 'Dune', release_date: '1984-12-14' }, { id: 2, title: 'Dune', release_date: '2021-09-15' }, { id: 3, title: 'Dune World', release_date: '2021-01-01' }]
    expect(bestCandidateId(r, 'dune', 2021)).toBe(2)
    expect(bestCandidateId(r, 'dune', null)).toBe(1)
    expect(bestCandidateId([], 'x', null)).toBeNull()
  })

  it('maps details to the page fields', () => {
    const e = toEnrichment({ backdrop_path: '/b.jpg', overview: ' Sand. ', release_date: '2024-02-27', genres: [{ name: 'Sci-Fi' }, { name: 'Drama' }],
      vote_average: 8.2, runtime: 166, credits: { crew: [{ job: 'Director', name: 'D. V.' }], cast: [{ name: 'A' }, { name: 'B' }] } })
    expect(e).toMatchObject({ backdrop: 'https://image.tmdb.org/t/p/w1280/b.jpg', plot: 'Sand.', year: '2024', genre: 'Sci-Fi, Drama', rating: 4.1, director: 'D. V.', cast: 'A, B', runtimeMin: 166 })
  })

  it('searches, picks, fetches details - and does nothing without a key', async () => {
    const calls: string[] = []
    const f = (async (url: string) => {
      calls.push(String(url).replace(/api_key=[^&]+/, 'api_key=K'))
      const body = String(url).includes('/search/') ? { results: [{ id: 7, title: 'Dune Part Two', release_date: '2024-02-27' }] } : { backdrop_path: '/x.jpg' }
      return new Response(JSON.stringify(body), { status: 200 })
    }) as typeof fetch
    expect(await enrich('movie', 'Dune Part Two', '2024', f, '')).toBeNull()
    const e = await enrich('movie', '|EN| Dune Part Two (2024)', '2024', f, 'secret')
    expect(e?.backdrop).toBe('https://image.tmdb.org/t/p/w1280/x.jpg')
    expect(calls).toEqual([
      'https://api.themoviedb.org/3/search/movie?api_key=K&query=Dune%20Part%20Two',
      'https://api.themoviedb.org/3/movie/7?api_key=K&append_to_response=credits',
    ])
  })
})
