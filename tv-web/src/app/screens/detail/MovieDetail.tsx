import { setFocus } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useState } from 'react'
import type { Enrichment } from '../../../data/tmdb'
import type { Movie, MovieInfo } from '../../../data/vodApi'
import { resumePointMs } from '../../../data/watchState'
import { clockOf } from '../../../player/vod/vodTypes'
import type { AppController } from '../../controller'
import { Focusable } from '../../Focusable'
import type { PlayRequest } from '../vod/playRequest'

/** A film: backdrop, facts, plot, and Resume / Play / Favourite. Info that fails to load is "no details", never an error page. */
export function MovieDetail({ movie, controller, onPlay }: { movie: Movie; controller: AppController; onPlay: (r: PlayRequest) => void }) {
  const [info, setInfo] = useState<MovieInfo | null>(null)
  const [failed, setFailed] = useState(false)
  const [tmdb, setTmdb] = useState<Enrichment | null>(null)
  const [fav, setFav] = useState(() => controller.favourites('movies').includes(movie.id))
  const entry = controller.watchEntry('movie', movie.id)
  const resume = resumePointMs(entry)

  useEffect(() => {
    let live = true
    controller.movieInfo(movie.id, movie.ext).then((i) => {
      if (!live) return
      setInfo(i)
      // Android enriches every film from TMDB, provider data or not (MovieDetailViewModelV2).
      controller.enrich('movie', movie.name, i.year).then((t) => { if (live) setTmdb(t) }).catch(() => undefined)
    }).catch(() => { if (live) { setFailed(true); controller.enrich('movie', movie.name, '').then((t) => { if (live) setTmdb(t) }).catch(() => undefined) } })
    return () => { live = false }
  }, [controller, movie])
  useEffect(() => { void setFocus('detail-play') }, [])

  const play = (startMs: number) => onPlay({
    kind: 'movie', id: movie.id, ext: info?.ext ?? movie.ext, title: movie.name, subtitle: info?.year ?? '', poster: movie.poster, startMs,
  })
  // TMDB first where it has the field (Android shows TMDB's), the provider's otherwise.
  const year = tmdb?.year || info?.year
  const genre = tmdb?.genre || info?.genre
  const rating = tmdb?.rating || info?.rating || movie.rating
  const runtime = info?.durationSecs ? info.durationSecs * 1000 : (tmdb?.runtimeMin ?? 0) * 60_000
  const plot = tmdb?.plot || info?.plot
  const director = tmdb?.director || info?.director
  const cast = tmdb?.cast || info?.cast
  // W4 QA N2: never a bare page - provider backdrop, then TMDB's, then the poster itself (blurred).
  const backdrop = info?.backdrop || tmdb?.backdrop || ''
  const posterBg = !backdrop ? (tmdb?.poster || movie.poster || info?.poster || '') : ''
  const facts = [year, runtime ? clockOf(runtime) : '', genre, rating ? `★ ${rating.toFixed(1)}` : '']
    .filter(Boolean).join(' · ')
  return (
    <div className="detail">
      {backdrop && <img className="detail-backdrop" src={backdrop} alt="" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
      {posterBg && <img className="detail-backdrop poster-bg" src={posterBg} alt="" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
      <div className="detail-shade" />
      <div className="detail-body">
        {movie.poster && <img className="detail-poster" src={movie.poster} alt="" onError={(e) => { e.currentTarget.style.visibility = 'hidden' }} />}
        <div className="detail-text">
          <h1>{movie.name}</h1>
          <div className="detail-facts">{facts || (failed ? 'No details from the provider' : 'Loading details…')}</div>
          {plot && <p className="detail-plot">{plot}</p>}
          {director && <div className="detail-credit"><b>Director</b> {director}</div>}
          {cast && <div className="detail-credit"><b>Cast</b> {cast}</div>}
          <div className="buttons detail-buttons">
            {resume > 0 ? (
              <>
                <Focusable focusKey="detail-play" className="button primary" onEnter={() => play(resume)}>▶ Resume from {clockOf(resume)}</Focusable>
                <Focusable focusKey="detail-start" className="button" onEnter={() => play(0)}>Start over</Focusable>
              </>
            ) : (
              <Focusable focusKey="detail-play" className="button primary" onEnter={() => play(0)}>▶ Play{entry?.watched ? ' again' : ''}</Focusable>
            )}
            <Focusable focusKey="detail-fav" className="button" onEnter={() => setFav(controller.toggleFavourite(movie.id, 'movies').includes(movie.id))}>
              {fav ? '★ Favourite' : '☆ Favourite'}
            </Focusable>
          </div>
        </div>
      </div>
    </div>
  )
}
