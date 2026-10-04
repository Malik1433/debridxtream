import { setFocus } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useState } from 'react'
import { cardText, cleanTitle } from '../../../data/titles'
import { normalizeTitle, type Enrichment } from '../../../data/tmdb'
import { matchTitlesOffThread } from '../../../data/titleMatch'
import type { Movie, MovieInfo } from '../../../data/vodApi'
import { resumePointMs } from '../../../data/watchState'
import { clockOf } from '../../../player/vod/vodTypes'
import type { AppController } from '../../controller'
import { spanAsync } from '../../perf/span'
import { SoftBackdrop } from '../../SoftBackdrop'
import { Focusable } from '../../Focusable'
import { Icon } from '../../icons'
import { HRow } from '../home/HRow'
import type { PlayRequest } from '../vod/playRequest'
import { TrailerOverlay } from './TrailerOverlay'

const hm = (min: number) => (min >= 60 ? `${Math.floor(min / 60)}h ${min % 60}m` : `${min}m`)

/**
 * A film, as Android's fragment_movie_detail_v2: full backdrop at α .45, the "IPTV Movies"
 * breadcrumb, FEATURE FILM, title, a metadata row, a resume bar, the plot, Watch Now · Trailer · ♡ ·
 * Mark Watched, DIRECTOR and CAST (photo chips), and SIMILAR MOVIES. TMDB fills what the provider
 * lacks, exactly as MovieDetailViewModelV2 does.
 */
export function MovieDetail({ movie, controller, onPlay, onOpenMovie }: {
  movie: Movie; controller: AppController; onPlay: (r: PlayRequest) => void; onOpenMovie: (m: Movie) => void
}) {
  const [info, setInfo] = useState<MovieInfo | null>(null)
  const [failed, setFailed] = useState(false)
  const [tmdb, setTmdb] = useState<Enrichment | null>(null)
  const [fav, setFav] = useState(() => controller.favourites('movies').includes(movie.id))
  const [trailer, setTrailer] = useState(false)
  const [, refresh] = useState(0)
  const entry = controller.watchEntry('movie', movie.id)
  const resume = resumePointMs(entry)
  const title = cardText(movie.name).title

  useEffect(() => {
    let live = true
    controller.movieInfo(movie.id, movie.ext).then((i) => {
      if (!live) return
      setInfo(i)
      controller.enrich('movie', movie.name, i.year).then((t) => { if (live) setTmdb(t) }).catch(() => undefined)
    }).catch(() => { if (live) { setFailed(true); controller.enrich('movie', movie.name, '').then((t) => { if (live) setTmdb(t) }).catch(() => undefined) } })
    return () => { live = false }
  }, [controller, movie])
  useEffect(() => { void setFocus('detail-play') }, [])

  // SIMILAR MOVIES: TMDB recommendations that this provider actually has. The scan of every title
  // runs in the worker when it holds the catalogue's names (round 10), so opening a detail page no
  // longer stalls the remote for ~0.9 s on the Samsung; without it, the same match runs here.
  const [similar, setSimilar] = useState<Movie[]>([])
  useEffect(() => {
    setSimilar([])
    if (!tmdb?.recommendations.length) return
    const all = controller.peek<Movie[]>('lib-items-movies') ?? []
    const titles = tmdb.recommendations.map((r) => r.title)
    let live = true
    void spanAsync('match:similar-movies', () => matchTitlesOffThread('movies', all, titles)).then((byTitle) => {
      if (!live) return
      const out: Movie[] = []
      for (const r of tmdb.recommendations) { const hit = byTitle.get(normalizeTitle(r.title) ?? ''); if (hit && hit.id !== movie.id && !out.includes(hit)) out.push(hit) }
      setSimilar(out.slice(0, 12))
    })
    return () => { live = false }
  }, [tmdb, controller, movie.id])

  const play = (startMs: number) => onPlay({
    kind: 'movie', id: movie.id, ext: info?.ext ?? movie.ext, title, subtitle: info?.year || tmdb?.year || '', poster: movie.poster, startMs,
  })
  const year = tmdb?.year || info?.year
  const genre = tmdb?.genre || info?.genre
  const rating = tmdb?.rating || info?.rating || movie.rating
  const runtimeMin = info?.durationSecs ? Math.round(info.durationSecs / 60) : tmdb?.runtimeMin ?? 0
  const plot = tmdb?.plot || info?.plot
  const director = tmdb?.director || info?.director
  const people = tmdb?.people.length ? tmdb.people : (info?.cast ? info.cast.split(',').slice(0, 6).map((n) => ({ name: n.trim(), photo: '' })) : [])
  const backdrop = info?.backdrop || tmdb?.backdrop || ''
  const posterBg = !backdrop ? (tmdb?.poster || movie.poster || info?.poster || '') : ''
  const durMs = runtimeMin * 60_000
  const toggleWatched = () => {
    controller.markWatched({ kind: 'movie', id: movie.id, title, poster: movie.poster, ext: movie.ext, durationMs: durMs }, !entry?.watched)
    refresh((n) => n + 1)
  }

  return (
    <div className="detail2">
      {backdrop && <img className="detail2-backdrop" src={backdrop} alt="" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
      {posterBg && <SoftBackdrop className="detail2-backdrop poster-bg" src={posterBg} w={48} h={27} />}
      <div className="detail2-scrim" />
      <div className="detail2-crumb"><b>IPTV</b> Movies</div>
      <div className="detail2-col">
        <div className="detail2-type">FEATURE FILM</div>
        <div className="detail2-title">{title}</div>
        <div className="detail2-meta">
          {rating > 0 && <span className="rating-badge">{rating.toFixed(1)} ★</span>}
          {[year, runtimeMin ? hm(runtimeMin) : '', genre].filter(Boolean).map((t, i) => <span key={i}>{i > 0 && <i>·</i>}{t}</span>)}
          {!year && !genre && !runtimeMin && <span className="muted">{failed ? 'No details from the provider' : 'Loading details…'}</span>}
        </div>
        {resume > 0 && entry && (
          <div className="resume-bar">
            <Icon name="play" size={28} />
            <div className="resume-text">
              <div><b>RESUME FROM {clockOf(resume)}</b>{entry.durationMs ? <span>{clockOf(entry.durationMs - resume)} LEFT</span> : null}</div>
              <div className="resume-progress"><div style={{ width: `${entry.durationMs ? (resume / entry.durationMs) * 100 : 0}%` }} /></div>
            </div>
          </div>
        )}
        {plot && <p className="detail2-plot">{plot}</p>}
        <div className="detail2-actions">
          <Focusable focusKey="detail-play" className="d-btn watch" onEnter={() => play(resume)}><Icon name="play" size={26} /> Watch Now</Focusable>
          {tmdb?.trailerKey && <Focusable focusKey="detail-trailer" className="d-btn" onEnter={() => setTrailer(true)}>Trailer</Focusable>}
          <Focusable focusKey="detail-fav" className={`d-btn square${fav ? ' on' : ''}`} onEnter={() => setFav(controller.toggleFavourite(movie.id, 'movies').includes(movie.id))}>
            <Icon name={fav ? 'favorite' : 'favorite_border'} size={28} />
          </Focusable>
          <Focusable focusKey="detail-watched" className="d-btn" onEnter={toggleWatched}>{entry?.watched ? 'Mark Unwatched' : 'Mark Watched'}</Focusable>
        </div>
        {entry?.watched && <div className="watched-badge">✓ WATCHED</div>}
        {(director || people.length > 0) && (
          <div className="detail2-credits">
            {director && <div><div className="credit-label">DIRECTOR</div><div className="credit-value">{director}</div></div>}
            {people.length > 0 && (
              <div>
                <div className="credit-label">CAST</div>
                <div className="cast-row">{people.slice(0, 6).map((p) => (
                  <div key={p.name} className="cast-chip">
                    {p.photo ? <img src={p.photo} alt="" onError={(e) => { e.currentTarget.style.display = 'none' }} /> : <span>{p.name.slice(0, 1)}</span>}
                    <small>{p.name}</small>
                  </div>
                ))}</div>
              </div>
            )}
          </div>
        )}
      </div>
      {similar.length > 0 && (
        <div className="detail2-similar">
          <HRow id="similar" title="SIMILAR MOVIES" items={similar} step={196} cardClass="sim-card" keyOf={(m) => m.id} onEnter={onOpenMovie}
            render={(m) => (
              <div className="sim-poster">
                <div className="poster-fallback">{cardText(m.name).title}</div>
                {m.poster && <img src={m.poster} alt="" loading="lazy" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
              </div>
            )} />
        </div>
      )}
      {trailer && tmdb?.trailerKey && <TrailerOverlay youtubeKey={tmdb.trailerKey} onClose={() => { setTrailer(false); void setFocus('detail-trailer') }} />}
    </div>
  )
}
