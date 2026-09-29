import { useFocusable } from '@noriginmedia/norigin-spatial-navigation'
import type { ReactNode } from 'react'

interface Props {
  focusKey: string
  className: string
  onEnter?: () => void
  onFocus?: () => void
  children: ReactNode
}

/**
 * Anything the remote can land on. Focus is always visible (the `focused` class draws the ring) and
 * OK acts - the TV rulebook in CLAUDE.md. Nothing here takes focus on its own after a data refresh.
 */
export function Focusable({ focusKey, className, onEnter, onFocus, children }: Props) {
  const { ref, focused } = useFocusable({ focusKey, onEnterPress: onEnter, onFocus: onFocus ? () => onFocus() : undefined })
  return (
    <div ref={ref} className={`${className}${focused ? ' focused' : ''}`}>
      {children}
    </div>
  )
}
