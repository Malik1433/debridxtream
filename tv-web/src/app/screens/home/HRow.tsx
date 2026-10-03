import { FocusContext, useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import { memo, useCallback, useState, type ReactNode } from 'react'

type CardProps<T> = {
  focusKey: string; index: number; item: T; className: string
  onEnter: (t: T) => void; onFocusAt: (i: number, t: T) => void; render: (t: T, focused: boolean) => ReactNode
}
/**
 * Memoized (round 7: Home spent 100-800 ms of JavaScript per focus move on the TV). Moving focus along
 * a row re-renders the row, but only the two cards whose focus changed draw again - as long as the
 * caller's `render` is stable (Home's rows are). An inline `render` simply re-renders every card, as before.
 */
const Card = memo(function Card<T>({ focusKey, index, item, className, onEnter, onFocusAt, render }: CardProps<T>) {
  const { ref, focused } = useFocusable({ focusKey, onEnterPress: () => onEnter(item), onFocus: () => onFocusAt(index, item) })
  return <div ref={ref} className={`${className}${focused ? ' focused' : ''}`}>{render(item, focused)}</div>
}) as <T>(p: CardProps<T>) => ReactNode

/**
 * One horizontal Home row (Android RecyclerView, horizontal). The strip slides under a fixed window
 * so the focused card stays in view - TVs scroll badly, a transform is smooth.
 */
type RowProps<T> = {
  id: string
  title: string
  count?: number
  items: T[]
  /** Card width + gap, px. */
  step: number
  cardClass: string
  keyOf: (t: T) => string
  onEnter: (t: T) => void
  render: (t: T, focused: boolean) => ReactNode
  onRowFocus?: () => void
  /** The card that took focus (Series: the Play button follows it). */
  onItemFocus?: (t: T) => void
}

/**
 * An empty row must not exist for the remote at all: a registered focusable with no box sits at
 * (0,0) and swallows ◀ from the hero (parity smoke 2026-10-02).
 */
export function HRow<T>(props: RowProps<T>) {
  return props.items.length ? <Row {...props} /> : null
}

function Row<T>({ id, title, count, items, step, cardClass, keyOf, onEnter, render, onRowFocus, onItemFocus }: {
  id: string
  title: string
  count?: number
  items: T[]
  /** Card width + gap, px. */
  step: number
  cardClass: string
  keyOf: (t: T) => string
  onEnter: (t: T) => void
  render: (t: T, focused: boolean) => ReactNode
  onRowFocus?: () => void
  onItemFocus?: (t: T) => void
}) {
  // W4 QA rounds 8-9: Home spent ~550 ms of JavaScript per press on the TV. On every arrow the focus
  // engine re-measured the row's cards (getBoundingClientRect, a forced layout) to find the neighbour -
  // in a row whose order IS the index. Left/right is now index - 1 / + 1 with no measuring at all;
  // up/down returns null, so the engine leaves the row exactly as before.
  const row = useFocusable({
    focusKey: `row-${id}`, saveLastFocusedChild: true, trackChildren: true,
    measureChildrenLayout: false,
    nextFocusResolver: (direction, focusKey, siblings) => {
      if (direction !== 'left' && direction !== 'right') return null
      const at = Number(focusKey.slice(id.length + 1))
      const want = `${id}-${at + (direction === 'right' ? 1 : -1)}`
      return siblings.find((s) => s.focusKey === want) ?? null
    },
  })
  const [idx, setIdx] = useState(0)
  const focusAt = useCallback((i: number, t: T) => { setIdx(i); onRowFocus?.(); onItemFocus?.(t) }, [onRowFocus, onItemFocus])
  const shift = Math.max(0, idx - 3) * step
  return (
    <FocusContext.Provider value={row.focusKey}>
      <section ref={row.ref} className="home-section" data-row={id}>
        {title && (
          <div className="home-section-head">
            <span className="home-section-title">{title}</span>
            {count !== undefined && <span className="home-section-count">{count}</span>}
          </div>
        )}
        <div className="home-strip" style={{ transform: `translateX(${-shift}px)` }}>
          {items.map((t, i) => (
            <Card key={keyOf(t)} focusKey={`${id}-${i}`} index={i} item={t} className={cardClass}
              onEnter={onEnter} onFocusAt={focusAt} render={render} />
          ))}
        </div>
      </section>
    </FocusContext.Provider>
  )
}
