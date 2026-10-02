import { setFocus } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useMemo, useState } from 'react'
import type { Show, ShowInfo } from '../../../data/vodApi'
import { resumePointMs } from '../../../data/watchState'
import { clockOf } from '../../../player/vod/vodTypes'
import type { AppController } from '../../controller'
import { Focusable } from '../../Focusable'
import { VirtualList } from '../live/VirtualList'
import { episodeRequest, type PlayRequest } from '../vod/playRequest'

/**
 * A series: facts and plot, a Continue / Play button that knows where the viewer is, season tabs,
 * and the episode list with each episode's progress. Episodes play in order across seasons.
 */
export function ShowDetail({ show, controller, onPlay }: { show: Show; controller: AppController; onPlay: (r: PlayRequest) => void }) {
  const [info, setInfo] = useState<ShowInfo | null>(null)
  const [failed, setFailed] = useState(false)
  const [season, setSeason] = useState<number | null>(null)
  const [fav, setFav] = useState(() => controller.favourites('shows').includes(show.id))
  const watched = useMemo(() => controller.watchedEpisodes(show.id), [controller, show.id])

  useEffect(() => {
    let live = true
    controller.showInfo(show.id).then((i) => { if (live) setInfo(i) }).catch(() => { if (live) setFailed(true) })
    return () => { live = false }
  }, [controller, show.id])

  const episodes = info?.episodes ?? []
  // Where to continue: the newest started-but-unfinished episode, else the one after the last finished.
  const resumeEp = useMemo(() => {
    const started = episodes.map((e) => ({ e, w: watched.get(e.id) })).filter((x) => x.w)
      .sort((a, b) => (b.w!.updatedAt - a.w!.updatedAt))[0]
    if (!started) return episodes[0] ?? null
    if (!started.w!.watched) return started.e
    const i = episodes.indexOf(started.e)
    return episodes[i + 1] ?? started.e
  }, [episodes, watched])
  useEffect(() => { if (info && season === null) setSeason(resumeEp?.season ?? info.seasons[0] ?? 1) }, [info, season, resumeEp])
  useEffect(() => { if (info) void setFocus('detail-play') }, [info])

  const inSeason = episodes.filter((e) => e.season === season)
  const ref = { id: show.id, name: show.name, poster: show.poster }
  const play = (i: number) => {
    const e = inSeason[i]
    if (e) onPlay(episodeRequest(e, ref, episodes, resumePointMs(watched.get(e.id) ?? null)))
  }
  const resumeMs = resumeEp ? resumePointMs(watched.get(resumeEp.id) ?? null) : 0
  const facts = [info?.year || show.year, info?.genre, (info?.rating || show.rating) ? `★ ${(info?.rating || show.rating).toFixed(1)}` : '',
    info ? `${info.seasons.length} season${info.seasons.length === 1 ? '' : 's'}` : ''].filter(Boolean).join(' · ')

  return (
    <div className="detail">
      {info?.backdrop && <img className="detail-backdrop" src={info.backdrop} alt="" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
      <div className="detail-shade" />
      <div className="detail-body show">
        <div className="detail-text">
          <h1>{show.name}</h1>
          <div className="detail-facts">{facts || (failed ? 'No details from the provider' : 'Loading episodes…')}</div>
          {info?.plot && <p className="detail-plot short">{info.plot}</p>}
          <div className="buttons detail-buttons">
            {resumeEp && (
              <Focusable focusKey="detail-play" className="button primary"
                onEnter={() => onPlay(episodeRequest(resumeEp, ref, episodes, resumeMs))}>
                ▶ {resumeMs > 0 ? 'Continue' : 'Play'} S{resumeEp.season} · E{resumeEp.number}{resumeMs > 0 ? ` (${clockOf(resumeMs)})` : ''}
              </Focusable>
            )}
            <Focusable focusKey="detail-fav" className="button" onEnter={() => setFav(controller.toggleFavourite(show.id, 'shows').includes(show.id))}>
              {fav ? '★ Favourite' : '☆ Favourite'}
            </Focusable>
          </div>
          {failed && episodes.length === 0 && <p className="muted">The provider did not send the episodes. Press BACK and try again.</p>}
          {info && episodes.length === 0 && !failed && <p className="muted">This series has no episodes yet.</p>}
          {info && info.seasons.length > 0 && (
            <div className="season-tabs">
              {info.seasons.map((s) => (
                <Focusable key={s} focusKey={`season-${s}`} className={`chip${s === season ? ' active' : ''}`} onEnter={() => setSeason(s)} onFocus={() => setSeason(s)}>
                  Season {s}
                </Focusable>
              ))}
            </div>
          )}
          {inSeason.length > 0 && (
            <VirtualList key={season ?? 0} focusKey="episodes" count={inSeason.length} rowHeight={84} visibleRows={5} onEnter={play}
              render={(i) => {
                const e = inSeason[i]
                const w = watched.get(e.id)
                const pct = w && w.durationMs ? Math.min(1, w.progressMs / w.durationMs) : 0
                return (
                  <div className="ep-row">
                    <span className="ep-num">{e.number || i + 1}</span>
                    <span className="name">{e.title}</span>
                    {e.durationSecs > 0 && <span className="ep-dur">{clockOf(e.durationSecs * 1000)}</span>}
                    {w?.watched ? <span className="ep-done">✓</span> : pct > 0 ? <span className="ep-bar"><span style={{ width: `${Math.round(pct * 100)}%` }} /></span> : null}
                  </div>
                )
              }} />
          )}
        </div>
      </div>
    </div>
  )
}
