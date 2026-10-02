import { setFocus, useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useMemo, useRef, useState } from 'react'
import { searchByName } from '../../../data/search'
import type { Movie, Show } from '../../../data/vodApi'
import type { LiveStream } from '../../../data/xtreamApi'
import type { AppController } from '../../controller'
import { Focusable } from '../../Focusable'
import { Poster } from '../library/Poster'

/** Samsung IME keys (keyboard/IME guide): Done and Cancel. */
const IME_DONE = 65376
const IME_CANCEL = 65385
const ROW_LIMIT = 12

/**
 * One box, three rows of answers (channels, films, series). OK on the box opens the TV's own
 * keyboard - the native IME, never an on-screen D-pad keyboard; Done or ▼ goes to the results.
 */
export function SearchScreen({ controller, onChannel, onMovie, onShow }: {
  controller: AppController; onChannel: (s: LiveStream) => void; onMovie: (m: Movie) => void; onShow: (s: Show) => void
}) {
  const [q, setQ] = useState('')
  const [data, setData] = useState<{ live: LiveStream[]; movies: Movie[]; shows: Show[] } | null>(null)
  const input = useRef<HTMLInputElement>(null)
  const box = useFocusable({ focusKey: 'search-box', onEnterPress: () => input.current?.focus() })

  useEffect(() => {
    let live = true
    Promise.all([controller.liveStreams(), controller.libraryItems<Movie>('movies'), controller.libraryItems<Show>('shows')])
      .then(([l, m, s]) => { if (live) setData({ live: l, movies: m, shows: s }) })
      .catch(() => { if (live) setData({ live: [], movies: [], shows: [] }) })
    return () => { live = false }
  }, [controller])
  useEffect(() => { void setFocus('search-box') }, [])

  const res = useMemo(() => data && q.trim().length >= 2 ? {
    live: searchByName(data.live, q, ROW_LIMIT), movies: searchByName(data.movies, q, ROW_LIMIT), shows: searchByName(data.shows, q, ROW_LIMIT),
  } : null, [data, q])
  const done = () => {
    input.current?.blur()
    const first = res?.live.length ? 'sr-live-0' : res?.movies.length ? 'sr-movie-0' : res?.shows.length ? 'sr-show-0' : 'search-box'
    void setFocus(first)
  }

  return (
    <div className="search">
      <h1>Search</h1>
      <div ref={box.ref} className={`search-box${box.focused ? ' focused' : ''}`}>
        <input ref={input} type="search" value={q} placeholder="Channel, film or series — press OK to type"
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => {
            // While typing, keys belong to the box: the same Enter must not also press the result it
            // moves focus to, and arrows must not walk the page behind the keyboard.
            e.stopPropagation()
            if (e.keyCode === IME_DONE || e.keyCode === 13 || e.keyCode === 40) { e.preventDefault(); done() }
            else if (e.keyCode === IME_CANCEL || e.keyCode === 27 || e.keyCode === 10009 || e.keyCode === 461 || (e.keyCode === 8 && !q)) {
              e.preventDefault(); input.current?.blur()
            }
          }} />
      </div>
      {!data && <p className="muted">Loading…</p>}
      {data && q.trim().length < 2 && <p className="muted">Type at least two letters.</p>}
      {res && !res.live.length && !res.movies.length && !res.shows.length && <p className="muted">Nothing matches “{q}”.</p>}
      {res && res.live.length > 0 && (
        <><h2>Channels</h2><div className="sr-row">{res.live.map((s, i) => (
          <Focusable key={s.id} focusKey={`sr-live-${i}`} className="sr-chan" onEnter={() => onChannel(s)}>
            {s.icon ? <img src={s.icon} alt="" onError={(e) => { e.currentTarget.style.visibility = 'hidden' }} /> : null}<span>{s.name}</span>
          </Focusable>))}</div></>
      )}
      {res && res.movies.length > 0 && (
        <><h2>Movies</h2><div className="sr-row">{res.movies.map((m, i) => (
          <Focusable key={m.id} focusKey={`sr-movie-${i}`} className="sr-poster" onEnter={() => onMovie(m)}><Poster src={m.poster} title={m.name} /></Focusable>))}</div></>
      )}
      {res && res.shows.length > 0 && (
        <><h2>Series</h2><div className="sr-row">{res.shows.map((s, i) => (
          <Focusable key={s.id} focusKey={`sr-show-${i}`} className="sr-poster" onEnter={() => onShow(s)}><Poster src={s.poster} title={s.name} sub={s.year} /></Focusable>))}</div></>
      )}
    </div>
  )
}
