import { doesFocusableExist, getCurrentFocusKey, setFocus } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { LibraryKind } from '../../../data/catalogueStore'
import { cardText } from '../../../data/titles'
import type { Movie, Show, VodCategory } from '../../../data/vodApi'
import type { AppController, AppState } from '../../controller'
import { Focusable } from '../../Focusable'
import { Icon } from '../../icons'
import { useDebounced } from '../../useDebounced'
import { indexCatalogue } from '../live/liveModel'
import { VirtualList } from '../live/VirtualList'
import { recall, remember, takeReturn } from './focusMemory'
import { FAV_ID, SORTS, itemsOf, libraryRows, sortItems, startRow, type SortMode } from './libraryModel'
import { VirtualGrid } from './VirtualGrid'

type Load<T> = { kind: 'loading' } | { kind: 'ready'; cats: VodCategory[]; items: T[] } | { kind: 'error'; message: string }

/** Android poster grid: 128×192 dp posters, 12 dp spacing → 280 px cells, five columns on a 1080p TV. */
const CELL_W = 280
const CELL_H = 470
const COLUMNS = 5

/**
 * Movies / Series, as Android's fragment_vod / fragment_series_vod: its own sidebar (DX badge, IPTV /
 * MOVIES, search bar, CATEGORIES list: All · Recently Added · Favorites · provider), a header with the
 * category title, `N titles` and the four sort chips, and the poster grid with quality, watched,
 * favourite and progress badges.
 */
export function LibraryScreen<T extends Movie | Show>({ kind, controller, state, onOpen, onSearch }: {
  kind: LibraryKind; controller: AppController; state: AppState; onOpen: (item: T) => void; onSearch: () => void
}) {
  const [load, setLoad] = useState<Load<T>>(() => {
    const cats = controller.peek<VodCategory[]>(`lib-cats-${kind}`), items = controller.peek<T[]>(`lib-items-${kind}`)
    return cats && items ? { kind: 'ready', cats, items } : { kind: 'loading' }
  })
  const [favs] = useState(() => controller.favourites(kind))
  const memKey = `lib:${kind}`
  const [row, setRow] = useState(() => recall(`${memKey}:row`) - 1)
  const [sort, setSort] = useState<SortMode>(() => (sessionStorage.getItem(`${memKey}:sort`) as SortMode | null) ?? 'recent')
  const libraryAt = state.library?.at ?? 0
  const isMovies = kind === 'movies'

  useEffect(() => {
    let live = true
    Promise.all([controller.libraryCategories(kind), controller.libraryItems<T>(kind)])
      .then(([cats, items]) => { if (live) setLoad((l) => (l.kind === 'ready' && l.items === items && l.cats === cats ? l : { kind: 'ready', cats, items })) })
      .catch((e: unknown) => { if (live) setLoad({ kind: 'error', message: e instanceof Error ? e.message : String(e) }) })
    return () => { live = false }
  }, [controller, kind, libraryAt])

  const items = load.kind === 'ready' ? load.items : []
  const favKey = favs.join(',')
  const rows = useMemo(() => (load.kind === 'ready'
    ? controller.memo(`lib-rows-${kind}:${favKey}`, () => libraryRows(load.cats, load.items, favs, isMovies ? 'All Movies' : 'All Series')) : []),
  [load, favs, favKey, controller, kind, isMovies])
  const index = useMemo(() => (load.kind === 'ready' ? controller.memo(`lib-index-${kind}`, () => indexCatalogue(load.items)) : undefined), [load, controller, kind])
  useEffect(() => { if (row < 0 && rows.length) setRow(startRow(rows)) }, [rows, row])
  const rowId = rows[row]?.id ?? ''
  const shown = useMemo(() => {
    const base = rowId === FAV_ID ? itemsOf(rowId, items, favs, index) : controller.memo(`lib-shown-${kind}-${rowId}`, () => itemsOf(rowId, items, favs, index))
    return rowId === FAV_ID ? base : controller.memo(`lib-sorted-${kind}-${rowId}-${sort}`, () => sortItems(base, sort))
  }, [rowId, items, favs, index, controller, kind, sort])
  const favSet = useMemo(() => new Set(favs), [favs])
  const gridKey = `${memKey}:${rowId}:${sort}`
  const watch = useMemo(() => controller.allWatch(), [controller])
  const cw = useMemo(() => controller.continueWatching(), [controller])

  // Android VodFocusController: arriving on Movies/Series puts focus on the grid's first poster.
  const returning = useRef(takeReturn())
  const toGrid = useRef(!returning.current)
  useEffect(() => {
    if (!toGrid.current || !shown.length || !doesFocusableExist('lib-grid-0')) return
    toGrid.current = false
    void setFocus('lib-grid-0')
  })
  useEffect(() => {
    if (!returning.current || !shown.length) return
    const key = `lib-grid-${Math.min(recall(gridKey), shown.length - 1)}`
    if (!doesFocusableExist(key)) return
    returning.current = false
    void setFocus(key)
  })
  useEffect(() => {
    if (row < 0 || doesFocusableExist(getCurrentFocusKey())) return
    void setFocus(shown.length ? `lib-grid-${Math.min(recall(gridKey), shown.length - 1)}` : `lib-cats-${row}`)
  })

  const pick = useDebounced((i: number) => { if (i !== row) { setRow(i); remember(`${memKey}:row`, i + 1) } })
  const chooseSort = (m: SortMode) => { setSort(m); try { sessionStorage.setItem(`${memKey}:sort`, m) } catch { /* private mode */ } }
  const syncing = state.librarySyncing && !state.library
  const noun = isMovies ? 'movies' : 'series'

  return (
    <div className="vod-screen">
      <div className="vod-sidebar">
        <div className="vod-logo">
          <div className="vod-dx">DX</div>
          <div><div className="vod-logo-t">IPTV</div><div className="vod-logo-s">{isMovies ? 'MOVIES' : 'SERIES'}</div></div>
        </div>
        <Focusable focusKey="lib-search" className="vod-search" onEnter={onSearch}>
          <Icon name="search" size={26} /><span>{isMovies ? 'Search movies…' : 'Search series…'}</span>
        </Focusable>
        <div className="vod-sidebar-divider" />
        <div className="vod-cat-title">Categories</div>
        {row >= 0 && rows.length > 0 && (
          <VirtualList focusKey="lib-cats" count={rows.length} rowHeight={88} visibleRows={8} startIndex={row}
            onFocusIndex={pick.call} onEnter={() => { pick.flush(); toGrid.current = true }}
            render={(i) => <div className={`vod-cat${i === row ? ' active' : ''}`}><span className="vod-cat-bar" /><span className="name">{rows[i].name}</span></div>} />
        )}
      </div>
      <div className="vod-main">
        <div className="vod-header">
          <div>
            <div className="vod-title">{rows[row]?.name ?? (isMovies ? 'All Movies' : 'All Series')}</div>
            <div className="vod-meta">{shown.length.toLocaleString()} titles</div>
          </div>
          <div className="vod-sorts">
            {SORTS.map((s) => (
              <Focusable key={s.mode} focusKey={`lib-sort-${s.mode}`} className={`vod-sort${s.mode === sort ? ' active' : ''}`} onEnter={() => chooseSort(s.mode)}>
                {s.label}
              </Focusable>
            ))}
          </div>
        </div>
        {load.kind === 'loading' && <div className="vod-skeleton">{Array.from({ length: 10 }, (_, i) => <div key={i} className="skeleton-card" />)}</div>}
        {load.kind === 'error' && <div className="vod-empty"><div>Could not read the list</div><small>{load.message}</small></div>}
        {load.kind === 'ready' && items.length === 0 && (
          <div className="vod-empty">
            <div>{syncing ? `Loading ${noun}…` : `No ${noun} available`}</div>
            <small>{state.library?.error ? `${state.library.error} — Settings → Update channels` : 'Please check your connection or try again later.'}</small>
          </div>
        )}
        {row >= 0 && items.length > 0 && shown.length === 0 && (
          <div className="vod-empty"><div>{rowId === FAV_ID ? 'No favorites yet' : 'Nothing here'}</div><small>{rowId === FAV_ID ? 'Open a title and press ♡ to add it.' : ''}</small></div>
        )}
        {shown.length > 0 && (
          <VirtualGrid key={gridKey} focusKey="lib-grid" count={shown.length} columns={COLUMNS} cellWidth={CELL_W} cellHeight={CELL_H} visibleRows={2}
            startIndex={recall(gridKey)} onFocusIndex={(i) => remember(gridKey, i)} onEnter={(i) => onOpen(shown[i])}
            render={(i) => {
              const it = shown[i]
              const { title, quality } = cardText(it.name)
              const w = isMovies ? watch.get(`movie:${it.id}`) : undefined
              const prog = isMovies ? (w && w.durationMs && !w.watched ? w.progressMs / w.durationMs : 0)
                : (() => { const e = cw.find((c) => c.seriesId === it.id); return e && e.durationMs ? e.progressMs / e.durationMs : 0 })()
              return (
                <div className="movie-card">
                  <div className="movie-poster">
                    <div className="poster-fallback">{title}</div>
                    {it.poster && <img src={it.poster} alt="" loading="lazy" onError={(e) => { e.currentTarget.style.display = 'none' }} />}
                    <span className="badge-quality">{quality}</span>
                    {w?.watched && <span className="badge-watched"><Icon name="check_circle" size={24} /></span>}
                    {favSet.has(it.id) && <span className="badge-fav"><Icon name="favorite" size={22} /></span>}
                    {prog > 0 && <div className="movie-progress"><div style={{ width: `${Math.round(prog * 100)}%` }} /></div>}
                  </div>
                  <div className="movie-title">{title}</div>
                </div>
              )
            }} />
        )}
      </div>
    </div>
  )
}
