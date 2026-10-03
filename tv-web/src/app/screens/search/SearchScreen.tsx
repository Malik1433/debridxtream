import { doesFocusableExist, getCurrentFocusKey, setFocus, useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { Movie, Show } from '../../../data/vodApi'
import type { LiveStream } from '../../../data/xtreamApi'
import { pushBackHandler } from '../../backStack'
import type { AppController } from '../../controller'
import { Focusable } from '../../Focusable'
import { Icon } from '../../icons'
import {
  appendChar, appendSpace, deleteChar, KEYS, MAX_QUERY, scopedResults, SCOPES, TRENDING, yearOf,
  type Catalogue, type Result, type Scope, type SearchContext,
} from './searchModel'

/** Samsung IME keys (keyboard/IME guide): Done and Cancel. */
const IME_DONE = 65376
const IME_CANCEL = 65385
/** Android SearchViewModel waits 300 ms after the last key before it searches. */
const DEBOUNCE_MS = 300
/** StremioPalette.bases: the placeholder each card shows until its poster arrives. */
const PALETTE = ['#1A2744', '#251635', '#102438', '#1C1030', '#0D2035', '#1E1535', '#13243A', '#1A1020', '#0E2235', '#1E1430', '#0A001A', '#2A1040']
const TYPE: Record<Result['kind'], { label: string; cls: string }> = {
  movie: { label: 'MOVIE', cls: 't-movie' }, series: { label: 'SERIES', cls: 't-series' }, live: { label: 'LIVE', cls: 't-live' },
}

/**
 * Android `layout-television/fragment_search`: "Search" + a query bar with a caret and a count; on
 * the left the All · Movies · Series · Live scope chips, the A–Z 0–9 key grid and SPACE · DEL ·
 * CLEAR; on the right Trending (or Recent) Searches, then the results grid. The TV's own keyboard
 * stays one OK away on the query bar (Samsung IME guide), for anyone who prefers it.
 */
export function SearchScreen({ controller, context, onChannel, onMovie, onShow }: {
  controller: AppController; context: SearchContext | null
  onChannel: (s: LiveStream) => void; onMovie: (m: Movie) => void; onShow: (s: Show) => void
}) {
  const [q, setQ] = useState('')
  const [debQ, setDebQ] = useState('')
  const [scope, setScope] = useState<Scope>(context?.scope ?? 'all')
  const [data, setData] = useState<Catalogue | null>(null)
  const [recent] = useState(() => controller.recentSearches())
  const input = useRef<HTMLInputElement>(null)
  const results = useRef<HTMLDivElement>(null)
  const bar = useFocusable({ focusKey: 'search-bar', onEnterPress: () => input.current?.focus() })

  useEffect(() => {
    let live = true
    Promise.all([controller.liveStreams(), controller.libraryItems<Movie>('movies'), controller.libraryItems<Show>('shows')])
      .then(([l, m, s]) => { if (live) setData({ live: l, movies: m, shows: s }) })
      .catch(() => { if (live) setData({ live: [], movies: [], shows: [] }) })
    return () => { live = false }
  }, [controller])
  useEffect(() => { void setFocus('key-0') }, [])
  useEffect(() => {
    const t = setTimeout(() => setDebQ(q), DEBOUNCE_MS)
    return () => clearTimeout(t)
  }, [q])

  // BACK from the results goes to the keys; from anywhere else it leaves (Android handleOnBackPressed).
  useEffect(() => pushBackHandler(() => {
    if (!getCurrentFocusKey().startsWith('sr-')) return false
    void setFocus('key-0')
    return true
  }), [])

  const items = useMemo(() => (data ? scopedResults(data, debQ, scope, context?.categoryId ?? null) : []), [data, debQ, scope, context])
  // Focus that sat on a result the new query removed goes back to the keys, never into the void.
  useEffect(() => {
    const k = getCurrentFocusKey()
    if (k.startsWith('sr-') && !doesFocusableExist(k)) void setFocus(items.length ? 'sr-0' : 'key-0')
  }, [items])

  const has = q.length > 0
  const terms = recent.length ? recent.slice(0, 8) : TRENDING
  const open = (r: Result) => {
    controller.recordSearch(debQ)
    if (r.kind === 'movie') onMovie(r.item)
    else if (r.kind === 'series') onShow(r.item)
    else onChannel(r.item)
  }
  const done = () => {
    input.current?.blur()
    void setFocus(items.length ? 'sr-0' : 'key-0')
  }
  /** Keep the focused card's row on screen inside the results column. */
  const reveal = (i: number) => {
    const box = results.current
    const el = box?.querySelector<HTMLElement>(`[data-i="${i}"]`)
    if (!box || !el) return
    const top = el.offsetTop, bottom = top + el.offsetHeight
    if (top < box.scrollTop) box.scrollTop = Math.max(0, top - 80)
    else if (bottom > box.scrollTop + box.clientHeight) box.scrollTop = bottom - box.clientHeight + 24
  }

  return (
    <div className="srch">
      <div className="srch-top">
        <h1>Search</h1>
        <div ref={bar.ref} className={`srch-bar${has ? ' has' : ''}${bar.focused ? ' focused' : ''}`}>
          <Icon name="search" size={24} />
          <div className="srch-field">
            {!has && <span className="srch-hint">{context?.categoryName ? `Search in ${context.categoryName}…` : 'Search movies, series, live channels…'}</span>}
            <input ref={input} type="search" value={q} maxLength={MAX_QUERY} className={has ? '' : 'empty'}
              onChange={(e) => setQ(e.target.value.toUpperCase().slice(0, MAX_QUERY))}
              onKeyDown={(e) => {
                // While the TV keyboard is up its keys belong to the box: Enter must not also press
                // the result it moves focus to, and arrows must not walk the page behind it.
                e.stopPropagation()
                if (e.keyCode === IME_DONE || e.keyCode === 13 || e.keyCode === 40) { e.preventDefault(); done() }
                else if (e.keyCode === IME_CANCEL || e.keyCode === 27 || e.keyCode === 10009 || e.keyCode === 461) { e.preventDefault(); input.current?.blur() }
              }} />
          </div>
          <span className="srch-caret" />
          <span className="srch-count">{has && data && debQ.trim().length >= 2 ? `${items.length} RESULTS` : ''}</span>
        </div>
      </div>

      <div className="srch-main">
        <div className="srch-left">
          <div className="srch-scopes">
            {SCOPES.map((s) => (
              <Focusable key={s.key} focusKey={`sc-${s.key}`} className={`srch-scope${scope === s.key ? ' on' : ''}`} onEnter={() => setScope(s.key)}>{s.label}</Focusable>
            ))}
          </div>
          <div className="srch-keys">
            {KEYS.map((k, i) => (
              <Focusable key={k} focusKey={`key-${i}`} className="srch-key" onEnter={() => setQ((x) => appendChar(x, k))}>{k}</Focusable>
            ))}
          </div>
          <div className="srch-actions">
            <Focusable focusKey="act-space" className="srch-act a-space" onEnter={() => setQ(appendSpace)}>SPACE</Focusable>
            <Focusable focusKey="act-del" className="srch-act a-del" onEnter={() => setQ(deleteChar)}>DEL</Focusable>
            <Focusable focusKey="act-clear" className="srch-act a-clear" onEnter={() => setQ('')}>CLEAR</Focusable>
          </div>
        </div>

        <div className="srch-right" ref={results}>
          {!has && (
            <>
              <div className="srch-head"><span className="srch-accent" />{recent.length ? 'Recent Searches' : 'Trending Searches'}</div>
              <div className="srch-trending">
                {terms.map((t, i) => (
                  <Focusable key={t} focusKey={`tr-${i}`} className="srch-chip" onEnter={() => setQ(t.toUpperCase().slice(0, MAX_QUERY))}>{t}</Focusable>
                ))}
              </div>
            </>
          )}
          {has && <div className="srch-head"><span className="srch-accent" />Results · “{q.trim()}”</div>}
          {has && !data && <p className="srch-wait">Loading…</p>}
          {has && data && items.length > 0 && (
            <div className="srch-grid">
              {items.map((r, i) => <ResultCard key={`${r.kind}-${r.item.id}`} r={r} i={i} controller={controller} onOpen={open} onFocus={reveal} />)}
            </div>
          )}
          {has && data && debQ.trim().length >= 2 && debQ === q && items.length === 0 && (
            <div className="srch-none">
              <Icon name="search" size={52} />
              <div className="srch-none-t">No matches for “{q.trim()}”</div>
              <div className="srch-none-s">Try a different title or check the spelling</div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function ResultCard({ r, i, controller, onOpen, onFocus }: {
  r: Result; i: number; controller: AppController; onOpen: (r: Result) => void; onFocus: (i: number) => void
}) {
  const t = TYPE[r.kind]
  const poster = r.kind === 'live' ? r.item.icon : r.item.poster
  const sub = r.kind === 'movie' ? (r.item.rating ? `⭐${r.item.rating}` : 'MOVIE')
    : r.kind === 'series' ? (yearOf(r.item.year) ?? 'SERIES')
    : (controller.epg.peek(r.item.id)?.[0]?.title || 'Live channel')
  return (
    <div className="srch-cell" data-i={i}>
      <Focusable focusKey={`sr-${i}`} className="srch-card" onEnter={() => onOpen(r)} onFocus={() => onFocus(i)}>
        <div className="srch-poster" style={{ background: `linear-gradient(135deg, ${PALETTE[i % PALETTE.length]}, #0B0F17)` }}>
          {poster && <img src={poster} alt="" loading="lazy" onError={(e) => { e.currentTarget.style.visibility = 'hidden' }} />}
          <div className="srch-scrim" />
          <span className={`srch-type ${t.cls}`}>{t.label}</span>
          {r.kind === 'live' && <span className="srch-live"><i />LIVE</span>}
        </div>
        <div className="srch-title">{r.item.name}</div>
        <div className="srch-meta">{sub}</div>
      </Focusable>
    </div>
  )
}
