import { FocusContext, useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import { useState, type ReactNode } from 'react'

function Card({ focusKey, onEnter, onFocus, className, children }: {
  focusKey: string; onEnter: () => void; onFocus: () => void; className: string; children: (focused: boolean) => ReactNode
}) {
  const { ref, focused } = useFocusable({ focusKey, onEnterPress: onEnter, onFocus: () => onFocus() })
  return <div ref={ref} className={`${className}${focused ? ' focused' : ''}`}>{children(focused)}</div>
}

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
}

/**
 * An empty row must not exist for the remote at all: a registered focusable with no box sits at
 * (0,0) and swallows ◀ from the hero (parity smoke 2026-10-02).
 */
export function HRow<T>(props: RowProps<T>) {
  return props.items.length ? <Row {...props} /> : null
}

function Row<T>({ id, title, count, items, step, cardClass, keyOf, onEnter, render, onRowFocus }: {
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
}) {
  const row = useFocusable({ focusKey: `row-${id}`, saveLastFocusedChild: true, trackChildren: true })
  const [idx, setIdx] = useState(0)
  const shift = Math.max(0, idx - 3) * step
  return (
    <FocusContext.Provider value={row.focusKey}>
      <section ref={row.ref} className="home-section" data-row={id}>
        <div className="home-section-head">
          <span className="home-section-title">{title}</span>
          {count !== undefined && <span className="home-section-count">{count}</span>}
        </div>
        <div className="home-strip" style={{ transform: `translateX(${-shift}px)` }}>
          {items.map((t, i) => (
            <Card key={keyOf(t)} focusKey={`${id}-${i}`} className={cardClass} onEnter={() => onEnter(t)}
              onFocus={() => { setIdx(i); onRowFocus?.() }}>
              {(f) => render(t, f)}
            </Card>
          ))}
        </div>
      </section>
    </FocusContext.Provider>
  )
}
