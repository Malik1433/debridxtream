import { describe, expect, it } from 'vitest'
import { episodeUrl, movieUrl, parseEpisodes, parseMovieInfo, parseMovies, parseShowInfo, parseShows } from './vodApi'

describe('vodApi', () => {
  it('parses movies and shows with the provider order and safe defaults', () => {
    const m = parseMovies([{ stream_id: 5, name: ' Dune ', category_id: 3, stream_icon: 'p.jpg', rating: '8', added: '1700000000', container_extension: 'mkv' }, { name: 'no id' }])
    expect(m).toEqual([{ id: '5', name: 'Dune', categoryId: '3', poster: 'p.jpg', rating: 4, added: 1_700_000_000_000, ext: 'mkv', order: 0 }])
    const s = parseShows([{ series_id: 9, name: 'Dark', cover: 'c.jpg', rating_5based: 4.5, releaseDate: '2017-12-01' }])
    expect(s[0]).toMatchObject({ id: '9', poster: 'c.jpg', rating: 4.5, year: '2017' })
    expect(parseMovies(false)).toEqual([])
  })

  it('reads movie info whatever shape the backdrop and info take', () => {
    const i = parseMovieInfo({ info: { plot: 'x', releasedate: '2021-10-22', duration_secs: '9300', backdrop_path: ['b1.jpg'], rating: '7.6' }, movie_data: { container_extension: 'mkv' } })
    expect(i).toMatchObject({ plot: 'x', year: '2021', durationSecs: 9300, backdrop: 'b1.jpg', rating: 3.8, ext: 'mkv' })
    expect(parseMovieInfo({ info: [] }, 'mp4')).toMatchObject({ plot: '', ext: 'mp4' })
  })

  it('reads episodes as a season map, a flat array, or nothing', () => {
    const map = parseEpisodes({ 2: [{ id: 21, episode_num: 1, title: 'B1', container_extension: 'mkv' }], 1: [{ id: 12, episode_num: 2 }, { id: 11, episode_num: 1, info: { duration_secs: 3000 } }] })
    expect(map.map((e) => e.id)).toEqual(['11', '12', '21'])
    expect(map[0]).toMatchObject({ season: 1, number: 1, durationSecs: 3000, ext: 'mp4' })
    expect(map[1].title).toBe('Episode 2')
    expect(parseEpisodes([{ id: 1, season: 3, episode_num: 4 }])[0]).toMatchObject({ season: 3, number: 4 })
    expect(parseEpisodes(false)).toEqual([])
    expect(parseEpisodes([])).toEqual([])
  })

  it('lists the seasons a show really has', () => {
    const s = parseShowInfo({ info: { name: 'x', backdrop_path: 'b.jpg' }, episodes: { 1: [{ id: 1 }], 3: [{ id: 2 }] } })
    expect(s.seasons).toEqual([1, 3])
    expect(s.backdrop).toBe('b.jpg')
  })

  it('builds play URLs from the account', () => {
    const a = { server: 'host:8080', username: 'u', password: 'p w' }
    expect(movieUrl(a, '5', 'mkv')).toBe('http://host:8080/movie/u/p%20w/5.mkv')
    expect(episodeUrl(a, '7', '')).toBe('http://host:8080/series/u/p%20w/7.mp4')
  })
})
