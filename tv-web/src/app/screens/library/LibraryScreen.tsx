import { doesFocusableExist, getCurrentFocusKey, setFocus } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { LibraryKind } from '../../../data/catalogueStore'
import type { Movie, Show, VodCategory } from '../../../data/vodApi'
import type { AppController, AppState } from '../../controller'
import { VirtualList } from '../live/VirtualList'
import { FAV_ID, itemsOf, libraryRows, startRow } from './libraryModel'
import { indexCatalogue } from '../live/liveModel'
import { Poster } from './Poster'
import { VirtualGrid } from './VirtualGrid'
import { recall, remember, takeReturn } from './focusMemory'

type Load<T> = { kind: 'loading' } | { kind: 'ready'; cats: VodCategory[]; items: T[] } | { kind: 'error'; message: string }

/** Movies or Series: categories | poster grid. OK on a poster opens its detail page. */
export function LibraryScreen<T extends Movie | Show>({ kind, controller, state, onOpen }: {
  kind: LibraryKind; controller: AppController; state: AppState; onOpen: (item: T) => void
}) {
  // Warm cache = the list on the first frame; "loading" only on a genuine cold start (W4 QA P1).
  const [load, setLoad] = useState<Load<T>>(() => {
    const cats = controller.peek<VodCategory[]>(`lib-cats-${kind}`), items = controller.peek<T[]>(`lib-items-${kind}`)
    return cats && items ? { kind: 'ready', cats, items } : { kind: 'loading' }
  })
  const favKind = kind
  const [favs] = useState(() => controller.favourites(favKind))
  const memKey = `lib:${kind}`
  const [row, setRow] = useState(() => recall(`${memKey}:row`) - 1)
  const libraryAt = state.library?.at ?? 0

  useEffect(() => {
    let live = true
    Promise.all([controller.libraryCategories(kind), controller.libraryItems<T>(kind)])
      .then(([cats, items]) => { if (live) setLoad((l) => (l.kind === 'ready' && l.items === items && l.cats === cats ? l : { kind: 'ready', cats, items })) })
      .catch((e: unknown) => { if (live) setLoad({ kind: 'error', message: e instanceof Error ? e.message : String(e) }) })
    return () => { live = false }
  }, [controller, kind, libraryAt])

  const items = load.kind === 'ready' ? load.items : []
  const favKey = favs.join(',')
  const rows = useMemo(() => (load.kind === 'ready' ? controller.memo(`lib-rows-${kind}:${favKey}`, () => libraryRows(load.cats, load.items, favs)) : []),
    [load, favs, favKey, controller, kind])
  const index = useMemo(() => (load.kind === 'ready' ? controller.memo(`lib-index-${kind}`, () => indexCatalogue(load.items)) : undefined), [load, controller, kind])
  useEffect(() => { if (row < 0 && rows.length) setRow(startRow(rows)) }, [rows, row])
  const rowId = rows[row]?.id ?? ''
  const shown = useMemo(() => (rowId === FAV_ID ? itemsOf(rowId, items, favs, index)
    : controller.memo(`lib-shown-${kind}-${rowId}`, () => itemsOf(rowId, items, favs, index))), [rowId, items, favs, index, controller, kind])
  const favSet = useMemo(() => new Set(favs), [favs])
  const gridKey = `${memKey}:${rowId}`
  const watch = useMemo(() => controller.continueWatching(), [controller])
  const progressOf = (id: string) => { const e = watch.find((w) => (kind === 'movies' ? w.kind === 'movie' && w.id === id : w.seriesId === id)); return e && e.durationMs ? e.progressMs / e.durationMs : 0 }

  // Back from a detail page: the poster it was opened from, once the grid is there again.
  const returning = useRef(takeReturn())
  useEffect(() => {
    if (!returning.current || !shown.length) return
    const key = `lib-grid-${Math.min(recall(gridKey), shown.length - 1)}`
    if (!doesFocusableExist(key)) return
    returning.current = false
    void setFocus(key)
  })

  // Arriving with focus on something that is gone (a Home tile, a closed detail): land on our list.
  useEffect(() => {
    if (row < 0 || doesFocusableExist(getCurrentFocusKey())) return
    void setFocus(shown.length ? `lib-grid-${Math.min(recall(gridKey), shown.length - 1)}` : `lib-cats-${row}`)
  })

  const syncing = state.librarySyncing && !state.library
  const title = kind === 'movies' ? 'Movies' : 'Series'
  return (
    <div className="library">
      <div className="live-col cats">
        <h2>{title}</h2>
        {load.kind === 'loading' && <p className="muted">Loading…</p>}
        {load.kind === 'error' && <p className="muted">Could not read the list ({load.message}).</p>}
        {load.kind === 'ready' && items.length === 0 && (
          <p className="muted">{syncing ? `Loading ${title.toLowerCase()} from your provider…` : state.library?.error ? `Could not load ${title.toLowerCase()} (${state.library.error}). Settings → Update channels to try again.` : `Your provider has no ${title.toLowerCase()}.`}</p>
        )}
        {row >= 0 && items.length > 0 && (
          <VirtualList focusKey="lib-cats" count={rows.length} rowHeight={66} visibleRows={12} startIndex={row}
            onFocusIndex={(i) => { if (i !== row) { setRow(i); remember(`${memKey}:row`, i + 1) } }}
            onEnter={() => { if (shown.length) void setFocus('lib-grid-0') }}
            render={(i) => <div className={`cat-row${i === row ? ' selected' : ''}`}><span className="name">{rows[i].name}</span><span className="count">{rows[i].count}</span></div>} />
        )}
      </div>
      <div className="lib-grid-col">
        <h2>{rows[row]?.name ?? ''}</h2>
        {row >= 0 && items.length > 0 && shown.length === 0 && (
          <p className="muted">{rowId === FAV_ID ? 'No favourites yet. Open a title and choose ☆ Favourite.' : 'Nothing here.'}</p>
        )}
        {shown.length > 0 && (
          <VirtualGrid key={gridKey} focusKey="lib-grid" count={shown.length} columns={6} cellWidth={180} cellHeight={300} visibleRows={3}
            startIndex={recall(gridKey)} onFocusIndex={(i) => remember(gridKey, i)} onEnter={(i) => onOpen(shown[i])}
            render={(i) => {
              const it = shown[i]
              const sub = 'year' in it && it.year ? it.year : it.rating ? `★ ${it.rating.toFixed(1)}` : ''
              return <Poster src={it.poster} title={it.name} sub={sub} fav={favSet.has(it.id)} progress={progressOf(it.id)} />
            }} />
        )}
      </div>
    </div>
  )
}
