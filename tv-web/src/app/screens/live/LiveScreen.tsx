import { doesFocusableExist, getCurrentFocusKey, pause, resume, setFocus } from '@noriginmedia/norigin-spatial-navigation'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { Programme } from '../../../data/epg'
import type { LiveCategory, LiveStream } from '../../../data/xtreamApi'
import { appKey } from '../../../keys'
import type { Platform } from '../../../platform'
import { pushBackHandler } from '../../backStack'
import type { AppController } from '../../controller'
import { Focusable } from '../../Focusable'
import { LiveDebugPanel } from './LiveDebugPanel'
import { LiveOsd } from './LiveOsd'
import { categoryRows, channelsOf, clock, startCategory, zapIndex } from './liveModel'
import { statusText } from './statusText'
import { useLiveEngine } from './useLiveEngine'
import { VirtualList } from './VirtualList'

const OSD_MS = 5_000
const EPG_REFRESH_MS = 60_000

type Load = { kind: 'loading' } | { kind: 'ready'; cats: LiveCategory[]; streams: LiveStream[] } | { kind: 'error'; message: string }

/**
 * Live TV (W3): categories | channels | preview. OK plays a channel in the preview; OK on the
 * playing channel (or the Full screen button) goes fullscreen, where ▲▼ and CH+/- zap, OK shows
 * the guide and BACK returns to the list on the channel now playing.
 */
export function LiveScreen({ platform, controller }: { platform: Platform; controller: AppController }) {
  const [load, setLoad] = useState<Load>({ kind: 'loading' })
  const [favs, setFavs] = useState<string[]>(() => controller.favourites())
  const [cat, setCat] = useState(-1)
  const [chanStart, setChanStart] = useState(0)
  const [listGen, setListGen] = useState(0)
  const focusedChan = useRef(0)
  const [playing, setPlaying] = useState<{ list: LiveStream[]; index: number } | null>(null)
  const [full, setFull] = useState(false)
  const [osdUntil, setOsdUntil] = useState(0)
  const [epg, setEpg] = useState<Programme[]>([])
  const [now, setNow] = useState(() => Date.now())
  const video = useRef<HTMLVideoElement>(null)
  const box = useRef<HTMLDivElement>(null)
  const { engine, status, relayout } = useLiveEngine(video, box, platform)
  const [debug, setDebug] = useState(false)
  const pendingFocus = useRef<string | null>(null)

  useEffect(() => {
    let live = true
    Promise.all([controller.liveCategories(), controller.liveStreams()])
      .then(([cats, streams]) => { if (live) setLoad({ kind: 'ready', cats, streams }) })
      .catch((e: unknown) => { if (live) setLoad({ kind: 'error', message: e instanceof Error ? e.message : String(e) }) })
    return () => { live = false }
  }, [controller])

  const streams = load.kind === 'ready' ? load.streams : []
  const rows = useMemo(() => (load.kind === 'ready' ? categoryRows(load.cats, load.streams, favs) : []), [load, favs])
  useEffect(() => { if (cat < 0 && rows.length) setCat(startCategory(rows)) }, [rows, cat])
  const catId = rows[cat]?.id ?? ''
  const channels = useMemo(() => channelsOf(catId, streams, favs), [catId, streams, favs])
  const favSet = useMemo(() => new Set(favs), [favs])
  const current = playing ? playing.list[playing.index] : null

  // Arriving from Home, focus sits on a tile that is gone: give it a home here (never steal it otherwise).
  useEffect(() => {
    if (cat < 0) return
    const key = pendingFocus.current
    if (key && doesFocusableExist(key)) { pendingFocus.current = null; void setFocus(key); return }
    if (!doesFocusableExist(getCurrentFocusKey())) void setFocus(channels.length ? `live-chans-${Math.min(chanStart, channels.length - 1)}` : `live-cats-${cat}`)
  })

  const play = useCallback((list: LiveStream[], index: number) => {
    const s = list[index]
    const url = s && controller.liveUrl(s.id)
    if (!s || !url || !engine) return
    engine.play({ id: s.id, name: s.name, url })
    setPlaying({ list, index })
  }, [controller, engine])

  const goFull = useCallback(() => { if (current) { setFull(true); setOsdUntil(Date.now() + OSD_MS) } }, [current])

  const onChannelEnter = useCallback((i: number) => {
    if (current && current.id === channels[i]?.id && status.kind !== 'failed') goFull()
    else play(channels, i)
  }, [current, channels, status.kind, goFull, play])

  const toggleFav = useCallback((id: string) => setFavs(controller.toggleFavourite(id)), [controller])

  // Fullscreen: the list gives up the remote (spatial nav paused) and BACK comes back to it.
  useEffect(() => {
    if (!full) return
    pause()
    const off = pushBackHandler(() => {
      setFull(false)
      return true
    })
    return () => { off(); resume() }
  }, [full])

  // AVPlay draws under the page, so in fullscreen nothing else may be painted over it, and its
  // picture has to follow the player box in and out of fullscreen.
  useLayoutEffect(() => {
    document.body.classList.toggle('player-full', full)
    relayout()
    return () => document.body.classList.remove('player-full')
  }, [full, relayout])

  // Back from fullscreen lands on the channel now playing, even after zapping far from where we left.
  const wasFull = useRef(false)
  useEffect(() => {
    if (wasFull.current && !full && playing) {
      const idx = channels.findIndex((c) => c.id === playing.list[playing.index]?.id)
      if (idx >= 0) { setChanStart(idx); setListGen((g) => g + 1); pendingFocus.current = `live-chans-${idx}` }
    }
    wasFull.current = full
  }, [full, playing, channels])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const k = appKey(e.keyCode, platform)
      if (k === 'debug') { e.preventDefault(); setDebug((d) => !d); return }
      if (k === 'favourite') {
        e.preventDefault()
        const target = full ? current : channels[focusedChan.current]
        if (target) toggleFav(target.id)
        return
      }
      if (!playing) return
      const delta = k === 'ch_up' || (full && k === 'up') ? -1 : k === 'ch_down' || (full && k === 'down') ? 1 : 0
      if (delta) {
        e.preventDefault()
        play(playing.list, zapIndex(playing.index, delta, playing.list.length))
        if (full) setOsdUntil(Date.now() + OSD_MS)
      } else if (full && k === 'enter') {
        e.preventDefault()
        setOsdUntil((u) => (u > Date.now() ? 0 : Date.now() + OSD_MS))
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [platform, full, playing, current, channels, play, toggleFav])

  // Now/next for the playing channel, refreshed while it plays.
  useEffect(() => {
    setEpg([])
    if (!current) return
    let live = true
    const fetchIt = () => { void controller.epg.nowNext(current.id).then((p) => { if (live) setEpg(p) }) }
    fetchIt()
    const id = setInterval(() => { setNow(Date.now()); fetchIt() }, EPG_REFRESH_MS)
    return () => { live = false; clearInterval(id) }
  }, [controller, current])

  useEffect(() => {
    if (osdUntil <= Date.now()) return
    const t = setTimeout(() => setNow(Date.now()), osdUntil - Date.now() + 50)
    return () => clearTimeout(t)
  }, [osdUntil])

  const pill = statusText(status)
  const osdShown = full && osdUntil > Date.now()

  return (
    <div className="live">
      <div className="live-col cats">
        <h2>Categories</h2>
        {load.kind === 'loading' && <p className="muted">Loading channels…</p>}
        {load.kind === 'error' && <p className="muted">Could not read the channel list ({load.message}).</p>}
        {cat >= 0 && (
          <VirtualList focusKey="live-cats" count={rows.length} rowHeight={66} visibleRows={12} startIndex={cat}
            onFocusIndex={(i) => { if (i !== cat) { setCat(i); setChanStart(0); focusedChan.current = 0 } }}
            onEnter={() => { if (channels.length) void setFocus(`live-chans-0`) }}
            render={(i) => (
              <div className={`cat-row${i === cat ? ' selected' : ''}`}>
                <span className="name">{rows[i].name}</span><span className="count">{rows[i].count}</span>
              </div>
            )} />
        )}
      </div>
      <div className="live-col chans">
        <h2>{rows[cat]?.name ?? 'Channels'}</h2>
        {cat >= 0 && channels.length === 0 && (
          <p className="muted">{catId === '__fav' ? 'No favourites yet. Press the yellow key on a channel, or use ☆ Favourite.' : 'No channels here.'}</p>
        )}
        {channels.length > 0 && (
          <VirtualList key={`${catId}:${listGen}`} focusKey="live-chans" count={channels.length} rowHeight={76} visibleRows={11}
            startIndex={chanStart} onFocusIndex={(i) => { focusedChan.current = i }} onEnter={onChannelEnter}
            render={(i) => {
              const c = channels[i]
              return (
                <div className={`chan-row${current?.id === c.id ? ' playing' : ''}`}>
                  <span className="num">{i + 1}</span>
                  {c.icon ? <img src={c.icon} alt="" loading="lazy" onError={(e) => { e.currentTarget.style.visibility = 'hidden' }} /> : <span className="noimg" />}
                  <span className="name">{c.name}</span>
                  {favSet.has(c.id) && <span className="fav">★</span>}
                  {current?.id === c.id && <span className="live-dot">▶</span>}
                </div>
              )
            }} />
        )}
      </div>
      <div className="live-col preview">
        <div ref={box} className={`live-player${full ? ' full' : ''}`}>
          <video ref={video} playsInline />
          {!current && <div className="player-hint">Press OK on a channel to watch it here</div>}
          {pill && <div className="pill">{pill}</div>}
          {osdShown && current && playing && (
            <LiveOsd number={playing.index + 1} name={current.name} favourite={favSet.has(current.id)} programmes={epg} now={now} />
          )}
        </div>
        {current && (
          <div className="preview-info">
            <div className="p-name">{current.name}</div>
            {epg[0] ? <div className="p-now"><b>{clock(epg[0].start)}</b> {epg[0].title}</div> : <div className="p-now muted">No programme guide</div>}
            {epg[1] && <div className="p-next"><b>{clock(epg[1].start)}</b> {epg[1].title}</div>}
            <div className="buttons">
              <Focusable focusKey="live-full" className="button" onEnter={goFull}>Full screen</Focusable>
              <Focusable focusKey="live-fav" className="button" onEnter={() => toggleFav(current.id)}>
                {favSet.has(current.id) ? '★ Favourite' : '☆ Favourite'}
              </Focusable>
            </div>
          </div>
        )}
      </div>
      {debug && engine && <LiveDebugPanel engine={engine} status={status} />}
    </div>
  )
}
