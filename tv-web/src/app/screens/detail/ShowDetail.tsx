import { setFocus } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useMemo, useState } from 'react'
import { cardText } from '../../../data/titles'
import type { Enrichment } from '../../../data/tmdb'
import type { Episode, Show, ShowInfo } from '../../../data/vodApi'
import { resumePointMs } from '../../../data/watchState'
import { clockOf } from '../../../player/vod/vodTypes'
import type { AppController } from '../../controller'
import { SoftBackdrop } from '../../SoftBackdrop'
import { Focusable } from '../../Focusable'
import { Icon } from '../../icons'
import { HRow } from '../home/HRow'
import { episodeRequest, type PlayRequest } from '../vod/playRequest'
import { SeasonPicker } from './SeasonPicker'
import { TrailerOverlay } from './TrailerOverlay'

const se = (e: Episode) => `S${String(e.season).padStart(2, '0')} · E${String(e.number).padStart(2, '0')}`

/**
 * A series, as Android's fragment_series_detail_v2: TV SERIES, title, `N SEASONS · M EPISODES`, plot,
 * Play/Resume S01 · E01 (following the focused episode, SeriesDetailActions.updatePlayButton) ·
 * Trailer · ♡, CREATOR + CAST, then EPISODES with the SEASON n ▾ selector, `w / t WATCHED`, and a
 * horizontal row of episode cards. Episodes play in order across seasons.
 */
export function ShowDetail({ show, controller, onPlay }: { show: Show; controller: AppController; onPlay: (r: PlayRequest) => void }) {
  const [info, setInfo] = useState<ShowInfo | null>(null)
  const [failed, setFailed] = useState(false)
  const [tmdb, setTmdb] = useState<Enrichment | null>(null)
  const [season, setSeason] = useState<number | null>(null)
  const [picking, setPicking] = useState(false)
  const [trailer, setTrailer] = useState(false)
  const [fav, setFav] = useState(() => controller.favourites('shows').includes(show.id))
  const watched = useMemo(() => controller.watchedEpisodes(show.id), [controller, show.id])
  const title = cardText(show.name).title

  useEffect(() => {
    let live = true
    controller.showInfo(show.id).then((i) => { if (live) setInfo(i) }).catch(() => { if (live) setFailed(true) })
    controller.enrich('tv', show.name, show.year).then((t) => { if (live) setTmdb(t) }).catch(() => undefined)
    return () => { live = false }
  }, [controller, show.id, show.name, show.year])

  const episodes = info?.episodes ?? []
  const resumeEp = useMemo(() => {
    const started = episodes.map((e) => ({ e, w: watched.get(e.id) })).filter((x) => x.w).sort((a, b) => b.w!.updatedAt - a.w!.updatedAt)[0]
    if (!started) return episodes[0] ?? null
    if (!started.w!.watched) return started.e
    return episodes[episodes.indexOf(started.e) + 1] ?? started.e
  }, [episodes, watched])
  const [target, setTarget] = useState<Episode | null>(null)
  const playEp = target ?? resumeEp
  useEffect(() => { if (info && season === null) setSeason(resumeEp?.season ?? info.seasons[0] ?? 1) }, [info, season, resumeEp])
  useEffect(() => { if (info) void setFocus('detail-play') }, [info])

  const inSeason = episodes.filter((e) => e.season === season)
  const seasonWatched = inSeason.filter((e) => watched.get(e.id)?.watched).length
  const ref = { id: show.id, name: title, poster: show.poster }
  const playEpisode = (e: Episode) => onPlay(episodeRequest(e, ref, episodes, resumePointMs(watched.get(e.id) ?? null)))
  const playMs = playEp ? resumePointMs(watched.get(playEp.id) ?? null) : 0
  const backdrop = info?.backdrop || tmdb?.backdrop || ''
  const posterBg = !backdrop ? (tmdb?.poster || info?.poster || show.poster || '') : ''
  const rating = info?.rating || show.rating || tmdb?.rating || 0
  const plot = info?.plot || tmdb?.plot
  const people = tmdb?.people.length ? tmdb.people : (info?.cast ? info.cast.split(',').slice(0, 6).map((n) => ({ name: n.trim(), photo: '' })) : [])
  const meta = [info?.year || show.year || tmdb?.year,
    info ? `${info.seasons.length} SEASON${info.seasons.length === 1 ? '' : 'S'} · ${episodes.length} EPISODES` : '',
    info?.genre || tmdb?.genre].filter(Boolean)

  return (
    <div className="detail2">
      {backdrop && <img className="detail2-backdrop" src={backdrop} alt="" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
      {posterBg && <SoftBackdrop className="detail2-backdrop poster-bg" src={posterBg} w={48} h={27} />}
      <div className="detail2-scrim" />
      <div className="detail2-crumb"><b>IPTV</b> / SERIES <span className="session-badge">XTREAM ACTIVE</span></div>
      <div className="detail2-col show">
        <div className="detail2-type">TV SERIES</div>
        <div className="detail2-title big">{title}</div>
        <div className="detail2-meta">
          {rating > 0 && <span className="rating-badge">{rating.toFixed(1)} ★</span>}
          {meta.map((t, i) => <span key={i}>{i > 0 && <i>·</i>}{t}</span>)}
          {!info && <span className="muted">{failed ? 'No details from the provider' : 'Loading episodes…'}</span>}
        </div>
        {plot && <p className="detail2-plot short">{plot}</p>}
        <div className="detail2-actions">
          {playEp && (
            <Focusable focusKey="detail-play" className="d-btn watch" onEnter={() => playEpisode(playEp)}>
              <Icon name="play" size={26} /> {playMs > 0 ? `Resume ${se(playEp)}` : `Play ${se(playEp)}`}
            </Focusable>
          )}
          {tmdb?.trailerKey && <Focusable focusKey="detail-trailer" className="d-btn" onEnter={() => setTrailer(true)}>Trailer</Focusable>}
          <Focusable focusKey="detail-fav" className={`d-btn square${fav ? ' on' : ''}`} onEnter={() => setFav(controller.toggleFavourite(show.id, 'shows').includes(show.id))}>
            <Icon name={fav ? 'favorite' : 'favorite_border'} size={28} />
          </Focusable>
        </div>
        {(tmdb?.director || people.length > 0) && (
          <div className="detail2-credits small">
            {tmdb?.director && <div><div className="credit-label">CREATOR</div><div className="credit-value">{tmdb.director}</div></div>}
            {people.length > 0 && <div><div className="credit-label">CAST</div><div className="credit-value">{people.slice(0, 4).map((p) => p.name).join(', ')}</div></div>}
          </div>
        )}
      </div>
      <div className="episodes-section">
        <div className="episodes-head">
          <span className="credit-label">EPISODES</span>
          {info && info.seasons.length > 0 && (
            <Focusable focusKey="season-selector" className="season-btn" onEnter={() => setPicking(true)}>SEASON {season ?? 1} ▾</Focusable>
          )}
          {inSeason.length > 0 && <span className="season-progress">{seasonWatched} / {inSeason.length} WATCHED</span>}
          {info && episodes.length === 0 && <span className="season-progress">{failed ? 'The provider did not send the episodes.' : 'This series has no episodes yet.'}</span>}
        </div>
        <HRow key={season ?? 0} id="eps" title="" items={inSeason} step={544} cardClass="ep-card" keyOf={(e) => e.id} onEnter={playEpisode}
          onItemFocus={setTarget}
          render={(e) => {
            const w = watched.get(e.id)
            const pct = w && w.durationMs && !w.watched ? Math.min(1, w.progressMs / w.durationMs) : 0
            return (
              <EpisodeCard e={e} poster={show.poster} watchedDone={Boolean(w?.watched)} pct={pct} />
            )
          }} />
      </div>
      <div className="hint-bar">▲▼◀▶ NAVIGATE <i>│</i> OK / ENTER SELECT <i>│</i> BACK / ESC RETURN</div>
      {picking && info && (
        <SeasonPicker seasons={info.seasons} current={season ?? 1} onClose={() => { setPicking(false); void setFocus('season-selector') }}
          onPick={(s) => { setSeason(s); setPicking(false); setTimeout(() => void setFocus('eps-0'), 50) }} />
      )}
      {trailer && tmdb?.trailerKey && <TrailerOverlay youtubeKey={tmdb.trailerKey} onClose={() => { setTrailer(false); void setFocus('detail-trailer') }} />}
    </div>
  )
}

function EpisodeCard({ e, poster, watchedDone, pct }: { e: Episode; poster: string; watchedDone: boolean; pct: number }) {
  return (
    <div className="ep-card-inner">
      <div className="ep-thumb">
        {(e.still || poster) && <img src={e.still || poster} alt="" loading="lazy" className={e.still ? '' : 'poster-bg'} onError={(ev) => { ev.currentTarget.style.display = 'none' }} />}
        <span className="ep-badge">E{e.number}</span>
        <div className="ep-overlay" />
        <div className="ep-text">
          <div className="ep-title">{e.title}</div>
          {e.durationSecs > 0 && <div className="ep-dur">{clockOf(e.durationSecs * 1000)}</div>}
        </div>
        <span className="ep-play"><Icon name="play" size={22} /></span>
        {watchedDone && <span className="ep-watched"><Icon name="check_circle" size={30} /></span>}
        {pct > 0 && <div className="movie-progress"><div style={{ width: `${Math.round(pct * 100)}%` }} /></div>}
      </div>
    </div>
  )
}
