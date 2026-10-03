import { doesFocusableExist, getCurrentFocusKey, pause, resume, setFocus } from '@noriginmedia/norigin-spatial-navigation'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { Programme } from '../../../data/epg'
import type { LiveCategory, LiveStream } from '../../../data/xtreamApi'
import { appKey, digitOf } from '../../../keys'
import { setScreenSaver, type Platform } from '../../../platform'
import { pushBackHandler } from '../../backStack'
import type { AppController } from '../../controller'
import { resumeLastLive } from '../../../data/livePrefs'
import { Focusable } from '../../Focusable'
import { LiveDebugPanel } from './LiveDebugPanel'
import { ChipRow } from './ChipRow'
import { FullscreenOsd, OSD_BUTTONS } from './FullscreenOsd'
import { cardText } from '../../../data/titles'
import { Icon } from '../../icons'
import { categoryRows, filterChips, channelsOf, clock, indexCatalogue, progress, startCategory, zapIndex } from './liveModel'
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
const NUMBER_WAIT_MS = 1_500
const LAST_KEY = 'dx.srv.live.last'

export function LiveScreen({ platform, controller, startChannelId = null, startFull = false, onStarted }: {
  platform: Platform; controller: AppController
  /** Open on this channel and play it (from Search). */
  startChannelId?: string | null
  onStarted?: () => void
  /** Home's Recent card and Search play the channel full screen; BACK then leaves Live (W4 QA R3). */
  startFull?: boolean
}) {
  // Warm cache = the list on the first frame; "loading" only on a genuine cold start (W4 QA P1).
  const [load, setLoad] = useState<Load>(() => {
    const cats = controller.peek<LiveCategory[]>('live-cats'), streams = controller.peek<LiveStream[]>('live-streams')
    return cats && streams ? { kind: 'ready', cats, streams } : { kind: 'loading' }
  })
  const [favs, setFavs] = useState<string[]>(() => controller.favourites())
  const [cat, setCat] = useState(-1)
  const [chanStart, setChanStart] = useState(0)
  const [listGen, setListGen] = useState(0)
  const focusedChan = useRef(0)
  const [playing, setPlaying] = useState<{ list: LiveStream[]; index: number } | null>(null)
  const [full, setFull] = useState(false)
  const [osdUntil, setOsdUntil] = useState(0)
  const [epg, setEpg] = useState<Programme[]>([])
  const [query, setQuery] = useState('')
  const queryInput = useRef<HTMLInputElement>(null)
  const [osdFocus, setOsdFocus] = useState(0)
  const [notice, setNotice] = useState('')
  const [focusIdx, setFocusIdx] = useState(0)
  const [, epgTick] = useState(0)
  const [now, setNow] = useState(() => Date.now())
  const video = useRef<HTMLVideoElement>(null)
  const box = useRef<HTMLDivElement>(null)
  const { engine, status, relayout } = useLiveEngine(video, box, platform)
  const [debug, setDebug] = useState(false)
  const pendingFocus = useRef<string | null>(null)
  const [numEntry, setNumEntry] = useState('')

  useEffect(() => {
    let live = true
    Promise.all([controller.liveCategories(), controller.liveStreams()])
      .then(([cats, streams]) => { if (live) setLoad((l) => (l.kind === 'ready' && l.streams === streams && l.cats === cats ? l : { kind: 'ready', cats, streams })) })
      .catch((e: unknown) => { if (live) setLoad({ kind: 'error', message: e instanceof Error ? e.message : String(e) }) })
    return () => { live = false }
  }, [controller])

  const streams = load.kind === 'ready' ? load.streams : []
  const favKey = favs.join(',')
  const rows = useMemo(() => (load.kind === 'ready' ? controller.memo(`live-rows:${favKey}`, () => categoryRows(load.cats, load.streams, favs)) : []),
    [load, favs, favKey, controller])
  const index = useMemo(() => (load.kind === 'ready' ? controller.memo('live-index', () => indexCatalogue(load.streams)) : undefined), [load, controller])
  // Where the viewer left off (category and channel), unless Search asked for a channel.
  useEffect(() => {
    if (cat >= 0 || !rows.length) return
    let last: { cat?: string; ch?: string } = {}
    // Android: back on the last channel only when Settings → Live TV → Resume Last Channel is on.
    if (resumeLastLive(localStorage)) { try { last = JSON.parse(localStorage.getItem(LAST_KEY) ?? '{}') } catch { /* none */ } }
    const want = startChannelId ? streams.find((x) => x.id === startChannelId)?.categoryId : last.cat
    const ci = want ? rows.findIndex((r) => r.id === want) : -1
    const c = ci >= 0 ? ci : startCategory(rows)
    const list = channelsOf(rows[c].id, streams, favs, index)
    const chId = startChannelId ?? last.ch
    const idx = chId ? list.findIndex((x) => x.id === chId) : -1
    setCat(c)
    if (idx >= 0) { setChanStart(idx); pendingFocus.current = `live-chans-${idx}` }
  }, [rows, cat, startChannelId, streams, favs, index])
  const catId = rows[cat]?.id ?? ''
  const catChannels = useMemo(() => channelsOf(catId, streams, favs, index), [catId, streams, favs, index])
  // The header's search pill filters the list in place (Android et_channel_search).
  const channels = catChannels
  // The search pill searches CATEGORIES (Android applyChipFilter), not channels.
  const chipIdx = useMemo(() => filterChips(rows, query), [rows, query])
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
    controller.recordChannel({ id: s.id, name: s.name, icon: s.icon })
    try { localStorage.setItem(LAST_KEY, JSON.stringify({ cat: rows[cat]?.id, ch: s.id })) } catch { /* storage blocked */ }
  }, [controller, engine, rows, cat])

  // Search asked for this channel: play it once the list and the engine are ready.
  useEffect(() => {
    if (!startChannelId || !engine || cat < 0 || !channels.some((x) => x.id === startChannelId)) return
    const idx = channels.findIndex((x) => x.id === startChannelId)
    if (idx >= 0) {
      play(channels, idx)
      if (startFull) { direct.current = true; setFull(true); setOsdUntil(Date.now() + OSD_MS) }
    }
    onStarted?.()
  }, [startChannelId, startFull, engine, cat, rows, channels, play, onStarted])

  // Samsung multitasking: hidden = the stream stops (one provider connection, nothing playing
  // unseen); back in front = the same channel again.
  useEffect(() => {
    const onVis = () => {
      if (!engine) return
      if (document.hidden) engine.stop()
      else if (playing) play(playing.list, playing.index)
    }
    document.addEventListener('visibilitychange', onVis)
    return () => document.removeEventListener('visibilitychange', onVis)
  }, [engine, playing, play])

  // The TV's screensaver must not cover a channel that is playing (Samsung).
  const showing = status.kind === 'playing' || status.kind === 'buffering'
  useEffect(() => { setScreenSaver(platform, !showing) }, [platform, showing])
  useEffect(() => () => setScreenSaver(platform, true), [platform])

  // Channel numbers: digits collect for 1.5 s, then jump to that position in the list being watched.
  useEffect(() => {
    if (!numEntry) return
    const t = setTimeout(() => {
      const list = playing?.list ?? channels
      const n = Number(numEntry)
      if (n >= 1 && n <= list.length) { play(list, n - 1); if (!full) { setChanStart(n - 1); setListGen((g) => g + 1); pendingFocus.current = `live-chans-${n - 1}` } }
      setNumEntry('')
    }, NUMBER_WAIT_MS)
    return () => clearTimeout(t)
  }, [numEntry, playing, channels, play, full])

  const direct = useRef(false)
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
      // Started straight into full screen: BACK goes back where the viewer came from, as Android's
      // player does; the App's own BACK takes it from here.
      if (direct.current) { direct.current = false; return false }
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
    if (wasFull.current && !full) direct.current = false
    wasFull.current = full
  }, [full, playing, channels])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const k = appKey(e.keyCode, platform)
      if (k === 'debug') { e.preventDefault(); setDebug((d) => !d); return }
      if (k === 'digit') { e.preventDefault(); setNumEntry((x) => (x + String(digitOf(e.keyCode))).slice(-4)); return }
      if (k === 'stop' && engine) { e.preventDefault(); engine.stop(); setPlaying(null); setFull(false); return }
      if (k === 'red' && debug && engine) { e.preventDefault(); engine.useAlternativePlayer(); return }
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
      } else if (full && (k === 'left' || k === 'right') && osdUntil > Date.now()) {
        e.preventDefault()
        setOsdFocus((f) => Math.max(0, Math.min(OSD_BUTTONS.length - 1, f + (k === 'left' ? -1 : 1))))
        setOsdUntil(Date.now() + OSD_MS)
      } else if (full && k === 'enter') {
        e.preventDefault()
        if (osdUntil <= Date.now()) { setOsdUntil(Date.now() + OSD_MS); return }
        const b = OSD_BUTTONS[osdFocus]?.key
        if (b === 'channels' || b === 'guide') { setFull(false); if (b === 'guide') pendingFocus.current = 'guide-0' }
        else { setNotice(b === 'cc' ? 'No subtitles on this channel' : 'This channel has one audio track'); setTimeout(() => setNotice(''), 3_000) }
        setOsdUntil(Date.now() + OSD_MS)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [platform, full, playing, current, channels, play, toggleFav, debug, engine, osdUntil, osdFocus])

  // Now/next for the playing channel, refreshed while it plays.
  useEffect(() => {
    setEpg([])
    if (!current) return
    let live = true
    const fetchIt = () => { void controller.epg.schedule(current.id).then((p) => { if (live) setEpg(p) }) }
    fetchIt()
    const id = setInterval(() => { setNow(Date.now()); fetchIt() }, EPG_REFRESH_MS)
    return () => { live = false; clearInterval(id) }
  }, [controller, current])

  useEffect(() => {
    if (osdUntil <= Date.now()) return
    const t = setTimeout(() => setNow(Date.now()), osdUntil - Date.now() + 50)
    return () => clearTimeout(t)
  }, [osdUntil])

  // Now-playing lines on the channel rows: the rows around focus, once focus rests (one request per
  // channel per minute, shared with the preview - EpgCache.schedule).
  useEffect(() => {
    const t = setTimeout(() => {
      const from = Math.max(0, focusIdx - 2), to = Math.min(channels.length, focusIdx + 6)
      let chain = Promise.resolve()
      for (let i = from; i < to; i++) {
        const id = channels[i]?.id
        if (id && !controller.epg.peek(id)) chain = chain.then(() => controller.epg.schedule(id).then(() => epgTick((n) => n + 1)))
      }
    }, 400)
    return () => clearTimeout(t)
  }, [focusIdx, channels, controller])

  // W4 QA R1, Android LiveCategoryController.handleCategoryClick: a chip changes the list on OK, and
  // OK takes focus into the list. Moving along the chips only moves focus.
  const chooseCat = (i: number) => {
    if (i === cat) { void setFocus('live-chans'); return }
    setCat(i); setChanStart(0); focusedChan.current = 0
    pendingFocus.current = 'live-chans-0'
  }
  const pill = statusText(status)
  const osdShown = full && osdUntil > Date.now()

  const nowProg = epg[0]
  const nextProg = epg[1]
  const playingNum = playing ? playing.index + 1 : 0
  const quality = current ? cardText(current.name).quality : ''
  const hdr = new Date(now)
  const clockText = `${((hdr.getHours() + 11) % 12) + 1}:${String(hdr.getMinutes()).padStart(2, '0')} ${hdr.getHours() < 12 ? 'AM' : 'PM'}`

  return (
    <div className="live2">
      <div className="live2-header">
        <span className="live2-dot" /><span className="live2-title">Live TV</span>
        <Focusable focusKey="live-search" className="live2-search" onEnter={() => queryInput.current?.focus()}>
          <Icon name="search" size={24} />
          <input ref={queryInput} value={query} placeholder="Search" onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              e.stopPropagation()
              if ([13, 40, 65376].includes(e.keyCode)) { e.preventDefault(); queryInput.current?.blur(); if (chipIdx.length) void setFocus('live-cats-0') }
              else if ([27, 10009, 461, 65385].includes(e.keyCode) || (e.keyCode === 8 && !query)) { e.preventDefault(); queryInput.current?.blur() }
            }} />
        </Focusable>
        {cat >= 0 && rows.length > 0 && (
          <ChipRow key={query} rows={chipIdx.map((i) => rows[i])} active={Math.max(0, chipIdx.indexOf(cat))}
            onEnter={(i) => chooseCat(chipIdx[i])} />
        )}
        <span className="live2-clock">{clockText}</span>
      </div>
      <div className="live2-body">
        <div className="live2-list">
          <div className="live2-list-head">
            <span>{(rows[cat]?.name ?? 'Channels').toUpperCase()}</span>
            <span className="count-pill">{channels.length}</span>
          </div>
          {load.kind === 'loading' && <div className="live2-state"><div className="spinner" /><span>Loading channels…</span></div>}
          {load.kind === 'error' && <div className="live2-state"><span>Could not read the channel list ({load.message}).</span></div>}
          {cat >= 0 && channels.length === 0 && load.kind === 'ready' && (
            <div className="live2-state"><span>{catId === '__fav' ? 'No favourites yet. Press the yellow key on a channel, or ★.' : 'No channels here.'}</span></div>
          )}
          {channels.length > 0 && (
            <VirtualList key={`${catId}:${listGen}`} focusKey="live-chans" count={channels.length} rowHeight={128} visibleRows={6}
              startIndex={chanStart} onFocusIndex={(i) => { focusedChan.current = i; setFocusIdx(i) }} onEnter={onChannelEnter}
              render={(i) => {
                const c = channels[i]
                const pe = controller.epg.peek(c.id)
                const pr = pe?.[0]
                const q = cardText(c.name).quality
                return (
                  <div className={`chan2${current?.id === c.id ? ' playing' : ''}`}>
                    <span className="chan2-num">{i + 1}</span>
                    <span className="chan2-logo">{c.icon ? <img src={c.icon} alt="" loading="lazy" onError={(e) => { e.currentTarget.style.visibility = 'hidden' }} /> : <b>{c.name.slice(0, 2).toUpperCase()}</b>}</span>
                    <span className="chan2-text">
                      <span className="chan2-name"><span>{c.name}</span>{/4K|FHD/.test(q) && <i className={`q-badge q-${q}`}>{q}</i>}</span>
                      <span className="chan2-now">{pr ? pr.title : current?.id === c.id ? 'Live' : ''}</span>
                      {pr && <span className="chan2-prog"><span style={{ width: `${Math.round(progress(pr.start, pr.end, now) * 100)}%` }} /></span>}
                    </span>
                    {favSet.has(c.id) && <span className="chan2-fav"><Icon name="live_star" size={22} /></span>}
                  </div>
                )
              }} />
          )}
        </div>
        <div className="live2-preview">
          <div ref={box} className={`live-player live2-frame${full ? ' full' : ''}`}>
            <video ref={video} playsInline />
            {!current && <div className="player-hint">Press OK on a channel to watch it here</div>}
            {current && !full && <span className="live2-badge-live"><i />LIVE</span>}
            {current && !full && quality && <span className={`live2-badge-q q-${quality}`}>{quality}</span>}
            {current && !full && (
              <div className="live2-lower">
                <div className="live2-now-label">NOW PLAYING</div>
                <div className="live2-now-row"><span className="live2-now-title">{nowProg?.title ?? current.name}</span>
                  {nowProg && <span className="live2-now-time">{clock(nowProg.start)} – {clock(nowProg.end)}</span>}</div>
                {nowProg && <div className="live2-now-prog"><div style={{ width: `${Math.round(progress(nowProg.start, nowProg.end, now) * 100)}%` }} /></div>}
              </div>
            )}
            {pill && <div className="pill">{pill}</div>}
            {notice && full && <div className="pill lo-notice">{notice}</div>}
            {osdShown && current && playing && (
              <FullscreenOsd number={playingNum} name={current.name} logo={current.icon} quality={quality} programmes={epg} now={now}
                focus={osdFocus} onAirNext={epg.slice(1)} />
            )}
          </div>
          <div className="live2-actions">
            <div className="live2-next">
              <div><div className="next-label">NEXT UP</div><div className="next-time">{nextProg ? clock(nextProg.start) : '—'}</div></div>
              <span className="next-sep" />
              <span className="next-title">{nextProg?.title ?? (current ? 'No programme guide' : 'Choose a channel')}</span>
            </div>
            <Focusable focusKey="live-full" className="live2-watch" onEnter={goFull}><Icon name="play" size={20} /> Watch</Focusable>
            <Focusable focusKey="live-fav" className={`live2-fav${current && favSet.has(current.id) ? ' on' : ''}`} onEnter={() => { if (current) toggleFav(current.id) }}>
              <Icon name="live_star" size={24} />
            </Focusable>
          </div>
          <div className="live2-guide-head"><span className="guide-bar" />Program Guide<small>{current ? current.name : ''}</small></div>
          <div className="live2-guide">
            {epg.length === 0 && <div className="guide-empty">{current ? 'No programme guide for this channel' : 'The guide shows here once a channel plays'}</div>}
            {epg.slice(0, 6).map((p2, i) => (
              <Focusable key={i} focusKey={`guide-${i}`} className={`guide-card${i === 0 ? ' onair' : ''}`} onEnter={goFull}>
                <div className="guide-time">{clock(p2.start)}{i === 0 && <span className="guide-onair">ON AIR</span>}</div>
                <div className="guide-title">{p2.title}</div>
                {i === 0 && <div className="guide-prog"><div style={{ width: `${Math.round(progress(p2.start, p2.end, now) * 100)}%` }} /></div>}
              </Focusable>
            ))}
          </div>
        </div>
      </div>
      {numEntry && <div className="num-entry">{numEntry}</div>}
      {debug && engine && <LiveDebugPanel engine={engine} status={status} />}
    </div>
  )

}
