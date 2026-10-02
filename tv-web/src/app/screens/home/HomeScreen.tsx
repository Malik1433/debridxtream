import { setFocus, useFocusable, FocusContext } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { RecentChannel } from '../../../data/recentLive'
import type { Enrichment } from '../../../data/tmdb'
import type { Movie, Show } from '../../../data/vodApi'
import type { WatchEntry } from '../../../data/watchState'
import { clockOf } from '../../../player/vod/vodTypes'
import type { AppController, AppState } from '../../controller'
import { Focusable } from '../../Focusable'
import { Icon } from '../../icons'
import { HRow } from './HRow'

/** Android HomeFragment: the hero pool is the first 3 of (Top-10 movies + Top-10 series), rotating every 7 s. */
const HERO_ROTATE_MS = 7_000
const HERO_FALLBACK = 'Watch this amazing content on DX Play. Cinematic experience.'
/** Where a focused row's top lands on screen (the Android NestedScrollView keeps it in the lower half). */
const ROW_ANCHOR_Y = 520

type Featured = { kind: 'movie'; item: Movie } | { kind: 'show'; item: Show }

function useClock(): string {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => { const id = setInterval(() => setNow(new Date()), 15_000); return () => clearInterval(id) }, [])
  const h = now.getHours(), m = now.getMinutes()
  return `${((h + 11) % 12) + 1}:${String(m).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`
}

/**
 * Home, as the Android TV app draws it (fragment_home_cinematic): a hero band with Play Now /
 * More Info / +, then Continue Watching, Trending Movies, Trending Series and Recent Live Channels
 * rows; a status bar (clock · KEY · IPTV) and the hint bar. IPTV-only data, like Android's normal
 * tier: Top-10 movies = the provider's newest films, Top-10 series = its first ten.
 */
export function HomeScreen({ state, controller, onMovie, onShow, onPlayMovie, onContinue, onChannel }: {
  state: AppState
  controller: AppController
  onMovie: (m: Movie) => void
  onShow: (s: Show) => void
  onPlayMovie: (m: Movie) => void
  onContinue: (e: WatchEntry) => void
  onChannel: (id: string) => void
}) {
  const [movies, setMovies] = useState<Movie[]>(() => controller.peek<Movie[]>('lib-items-movies') ?? [])
  const [shows, setShows] = useState<Show[]>(() => controller.peek<Show[]>('lib-items-shows') ?? [])
  const libAt = state.library?.at ?? 0
  useEffect(() => {
    let live = true
    controller.libraryItems<Movie>('movies').then((m) => { if (live) setMovies(m) }).catch(() => undefined)
    controller.libraryItems<Show>('shows').then((s) => { if (live) setShows(s) }).catch(() => undefined)
    return () => { live = false }
  }, [controller, libAt])

  const topMovies = useMemo(() => controller.memo(`home-top-movies:${movies.length}`, () => [...movies].sort((a, b) => b.added - a.added).slice(0, 10)), [controller, movies])
  const topShows = useMemo(() => shows.slice(0, 10), [shows])
  const cont = useMemo(() => controller.continueWatching(), [controller])
  const recent = useMemo<RecentChannel[]>(() => controller.recentChannels(), [controller])
  const pool = useMemo<Featured[]>(() => [
    ...topMovies.map((m) => ({ kind: 'movie' as const, item: m })), ...topShows.map((s) => ({ kind: 'show' as const, item: s })),
  ].slice(0, 3), [topMovies, topShows])

  const [heroIdx, setHeroIdx] = useState(0)
  const [heroFocused, setHeroFocused] = useState(false)
  useEffect(() => {
    if (pool.length < 2 || heroFocused) return
    const id = setInterval(() => setHeroIdx((i) => (i + 1) % pool.length), HERO_ROTATE_MS)
    return () => clearInterval(id)
  }, [pool.length, heroFocused])
  const hero = pool[heroIdx % Math.max(1, pool.length)]
  const [tmdb, setTmdb] = useState<Enrichment | null>(null)
  useEffect(() => {
    setTmdb(null)
    if (!hero) return
    let live = true
    controller.enrich(hero.kind === 'movie' ? 'movie' : 'tv', hero.item.name, 'year' in hero.item ? hero.item.year : '').then((t) => { if (live) setTmdb(t) }).catch(() => undefined)
    return () => { live = false }
  }, [controller, hero])
  const [favs, setFavs] = useState(() => ({ movies: controller.favourites('movies'), shows: controller.favourites('shows') }))
  const heroFav = hero ? favs[hero.kind === 'movie' ? 'movies' : 'shows'].includes(hero.item.id) : false

  // Vertical scroll: the focused row's top sits at ROW_ANCHOR_Y; the hero takes the top.
  const page = useRef<HTMLDivElement>(null)
  const [scrollY, setScrollY] = useState(0)
  const scrollTo = (rowId: string | null) => {
    if (!rowId) { setScrollY(0); return }
    const el = page.current?.querySelector<HTMLElement>(`[data-row="${rowId}"]`)
    if (!el) return
    // offsetTop up to the page (layout px, unaffected by the stage's scale transform).
    let y = 0
    for (let n: HTMLElement | null = el; n && n !== page.current; n = n.offsetParent as HTMLElement | null) y += n.offsetTop
    setScrollY(Math.max(0, y - ROW_ANCHOR_Y))
  }
  const heroBox = useFocusable({ focusKey: 'home-hero', saveLastFocusedChild: true, trackChildren: true,
    onFocus: () => { setHeroFocused(true); scrollTo(null) }, onBlur: () => setHeroFocused(false) })
  useEffect(() => { void setFocus('hero-watch') }, [])

  const clock = useClock()
  const lib = state.library
  const licence = state.license.kind === 'active' ? (state.license.trialDaysLeft !== null ? `TRIAL · ${state.license.trialDaysLeft}D` : 'ACTIVE') : 'LOCKED'
  const backdrop = hero ? (tmdb?.backdrop || (hero.kind === 'movie' ? hero.item.poster : hero.item.poster)) : ''
  const rating = hero ? (tmdb?.rating || hero.item.rating) : 0
  const year = tmdb?.year || (hero && 'year' in hero.item ? hero.item.year : '')

  return (
    <div className="home">
      {backdrop && <img key={backdrop} className={`home-hero-bg${tmdb?.backdrop ? '' : ' poster-bg'}`} src={backdrop} alt="" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
      <div className="home-hero-scrim" />
      <div ref={page} className="home-page" style={{ transform: `translateY(${-scrollY}px)` }}>
        <FocusContext.Provider value={heroBox.focusKey}>
          <div ref={heroBox.ref} className="home-hero">
            {hero ? (
              <div className="hero-content" key={`${hero.kind}-${hero.item.id}`}>
                <div className="hero-trending">#{heroIdx + 1} TRENDING TODAY</div>
                <div className="hero-title">{hero.item.name}</div>
                <div className="hero-meta">
                  {rating > 0 && <span className="hero-rating">{rating.toFixed(1)} ★</span>}
                  {year && <span>{year}</span>}
                  {year && tmdb?.genre && <span className="dot">·</span>}
                  {tmdb?.genre && <span>{tmdb.genre}</span>}
                </div>
                <div className="hero-desc">{tmdb?.plot || HERO_FALLBACK}</div>
                <div className="hero-buttons">
                  <Focusable focusKey="hero-watch" className="hero-btn play"
                    onEnter={() => (hero.kind === 'movie' ? onPlayMovie(hero.item) : onShow(hero.item))}>
                    <Icon name="play" size={22} /> Play Now
                  </Focusable>
                  <Focusable focusKey="hero-details" className="hero-btn glass" onEnter={() => (hero.kind === 'movie' ? onMovie(hero.item) : onShow(hero.item))}>
                    <Icon name="hero_info" size={20} /> More Info
                  </Focusable>
                  <Focusable focusKey="hero-fav" className={`hero-btn glass square${heroFav ? ' on' : ''}`}
                    onEnter={() => {
                      const k = hero.kind === 'movie' ? 'movies' : 'shows'
                      setFavs((f) => ({ ...f, [k]: controller.toggleFavourite(hero.item.id, k) }))
                    }}>
                    <Icon name={heroFav ? 'favorite' : 'hero_plus'} size={24} />
                  </Focusable>
                  {pool.length > 1 && <div className="hero-dots">{pool.map((_, i) => <span key={i} className={i === heroIdx ? 'on' : ''} />)}</div>}
                </div>
              </div>
            ) : (
              <div className="hero-content">
                <div className="hero-trending">DX PLAY</div>
                <div className="hero-title">{state.providerName ?? 'Welcome'}</div>
                <div className="hero-desc">{state.librarySyncing ? 'Loading movies and series from your provider…' : 'Live TV, movies and series from your provider.'}</div>
                <div className="hero-buttons"><Focusable focusKey="hero-watch" className="hero-btn play" onEnter={() => onChannel(recent[0]?.id ?? '')}><Icon name="live_tv" size={22} /> Live TV</Focusable></div>
              </div>
            )}
          </div>
        </FocusContext.Provider>

        <div className="home-rows">
          <HRow id="cw" title="Continue Watching" count={cont.length} items={cont} step={312} cardClass="cw-card" onRowFocus={() => scrollTo('cw')}
            keyOf={(e) => `${e.kind}-${e.id}`} onEnter={onContinue}
            render={(e) => (
              <>
                <div className="cw-img">
                  {e.poster && <img src={e.poster} alt="" loading="lazy" onError={(ev) => { ev.currentTarget.style.display = 'none' }} />}
                  <div className="card-footer-grad" />
                  <div className="cw-progress"><div style={{ width: `${e.durationMs ? Math.min(100, (e.progressMs / e.durationMs) * 100) : 0}%` }} /></div>
                </div>
                <div className="cw-title">{e.seriesName ? `${e.seriesName} · S${e.season} E${e.episode}` : e.title}</div>
                <div className="cw-sub">{clockOf(e.progressMs)}{e.durationMs ? ` / ${clockOf(e.durationMs)}` : ''}</div>
              </>
            )} />
          <HRow id="tm" title="Trending Movies" count={topMovies.length} items={topMovies} step={280} cardClass="top-card" onRowFocus={() => scrollTo('tm')}
            keyOf={(m) => m.id} onEnter={onMovie}
            render={(m) => <TopCard poster={m.poster} title={m.name} sub={m.rating ? `★ ${m.rating.toFixed(1)}` : ''} />} />
          <HRow id="ts" title="Trending Series" count={topShows.length} items={topShows} step={280} cardClass="top-card" onRowFocus={() => scrollTo('ts')}
            keyOf={(s) => s.id} onEnter={onShow}
            render={(s) => <TopCard poster={s.poster} title={s.name} sub={[s.year, s.rating ? `★ ${s.rating.toFixed(1)}` : ''].filter(Boolean).join(' · ')} />} />
          <HRow id="rl" title="Recent Live Channels" count={recent.length} items={recent} step={280} cardClass="live-card" onRowFocus={() => scrollTo('rl')}
            keyOf={(c) => c.id} onEnter={(c) => onChannel(c.id)}
            render={(c) => (
              <div className="live-card-glass">
                <span className="live-badge">LIVE</span>
                {c.icon ? <img src={c.icon} alt="" onError={(e) => { e.currentTarget.style.visibility = 'hidden' }} /> : <span className="live-initials">{c.name.slice(0, 2).toUpperCase()}</span>}
                <span className="live-name">{c.name}</span>
              </div>
            )} />
          {!topMovies.length && !topShows.length && !cont.length && !recent.length && (
            <p className="muted home-empty">{lib ? 'Nothing to show yet.' : 'Loading your library…'}</p>
          )}
        </div>
      </div>

      <div className="status-bar">
        <span className="status-clock">{clock}</span>
        <span className="status-badge"><small>KEY</small><b className="cyan">{state.activationCode}</b></span>
        <span className="status-badge"><Icon name="live_tv" size={20} /><small>IPTV</small><b className={state.license.kind === 'active' ? 'green' : 'red'}>{licence}</b></span>
      </div>
      <div className="hint-bar">
        <b>▲▼◀▶</b> NAVIGATE <i>│</i> <b>OK</b> SELECT <i>│</i> <b>BACK</b> EXIT
      </div>
    </div>
  )
}

function TopCard({ poster, title, sub }: { poster: string; title: string; sub: string }) {
  return (
    <>
      <div className="top-poster">
        <div className="poster-fallback">{title}</div>
        {poster && <img src={poster} alt="" loading="lazy" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
        <div className="card-footer-grad" />
      </div>
      <div className="top-title">{title}</div>
      {sub && <div className="top-sub">{sub}</div>}
    </>
  )
}
