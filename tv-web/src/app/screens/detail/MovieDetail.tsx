import { setFocus } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useState } from 'react'
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
  const [fav, setFav] = useState(() => controller.favourites('movies').includes(movie.id))
  const entry = controller.watchEntry('movie', movie.id)
  const resume = resumePointMs(entry)

  useEffect(() => {
    let live = true
    controller.movieInfo(movie.id, movie.ext).then((i) => { if (live) setInfo(i) }).catch(() => { if (live) setFailed(true) })
    return () => { live = false }
  }, [controller, movie])
  useEffect(() => { void setFocus('detail-play') }, [])

  const play = (startMs: number) => onPlay({
    kind: 'movie', id: movie.id, ext: info?.ext ?? movie.ext, title: movie.name, subtitle: info?.year ?? '', poster: movie.poster, startMs,
  })
  const facts = [info?.year, info?.durationSecs ? clockOf(info.durationSecs * 1000) : '', info?.genre, (info?.rating || movie.rating) ? `★ ${(info?.rating || movie.rating).toFixed(1)}` : '']
    .filter(Boolean).join(' · ')
  return (
    <div className="detail">
      {info?.backdrop && <img className="detail-backdrop" src={info.backdrop} alt="" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
      <div className="detail-shade" />
      <div className="detail-body">
        {movie.poster && <img className="detail-poster" src={movie.poster} alt="" onError={(e) => { e.currentTarget.style.visibility = 'hidden' }} />}
        <div className="detail-text">
          <h1>{movie.name}</h1>
          <div className="detail-facts">{facts || (failed ? 'No details from the provider' : 'Loading details…')}</div>
          {info?.plot && <p className="detail-plot">{info.plot}</p>}
          {info?.director && <div className="detail-credit"><b>Director</b> {info.director}</div>}
          {info?.cast && <div className="detail-credit"><b>Cast</b> {info.cast}</div>}
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
