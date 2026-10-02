import { pause as pauseNav, resume as resumeNav } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useState } from 'react'
import { appKey, digitOf } from '../keys'
import type { Platform } from '../platform'
import { PIN_LENGTH } from '../data/parental'
import { pushBackHandler } from './backStack'

/**
 * Four digits, remote-only: the number keys type straight in; ▲▼ change the digit and ◀▶ move (for
 * remotes, like VIDAA's, whose digits are the D-pad); OK goes on, and on the last digit confirms.
 * BACK cancels. Digits are drawn as dots - a child watching should not learn the PIN.
 */
export function PinDialog({ platform, title, error, onDone, onCancel }: {
  platform: Platform; title: string; error?: string; onDone: (pin: string) => void; onCancel: () => void
}) {
  const [digits, setDigits] = useState<number[]>(() => Array(PIN_LENGTH).fill(0))
  const [pos, setPos] = useState(0)
  const [typed, setTyped] = useState<boolean[]>(() => Array(PIN_LENGTH).fill(false))

  useEffect(() => { pauseNav(); return () => resumeNav() }, [])
  useEffect(() => pushBackHandler(() => { onCancel(); return true }), [onCancel])
  useEffect(() => {
    const set = (i: number, v: number) => {
      setDigits((d) => d.map((x, j) => (j === i ? v : x)))
      setTyped((t) => t.map((x, j) => (j === i ? true : x)))
    }
    const finish = (d: number[]) => { onDone(d.join('')); setDigits(Array(PIN_LENGTH).fill(0)); setTyped(Array(PIN_LENGTH).fill(false)); setPos(0) }
    const onKey = (e: KeyboardEvent) => {
      const k = appKey(e.keyCode, platform)
      if (!k || k === 'back') return
      e.preventDefault()
      if (k === 'digit') {
        const d = digits.map((x, j) => (j === pos ? digitOf(e.keyCode) : x))
        set(pos, digitOf(e.keyCode))
        if (pos === PIN_LENGTH - 1) finish(d); else setPos(pos + 1)
      } else if (k === 'up' || k === 'down') set(pos, (digits[pos] + (k === 'up' ? 1 : 9)) % 10)
      else if (k === 'left') setPos(Math.max(0, pos - 1))
      else if (k === 'right') setPos(Math.min(PIN_LENGTH - 1, pos + 1))
      else if (k === 'enter') { setTyped((t) => t.map((x, j) => (j === pos ? true : x))); if (pos === PIN_LENGTH - 1) finish(digits); else setPos(pos + 1) }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [platform, digits, pos, onDone])

  return (
    <div className="dialog-scrim">
      <div className="dialog pin-dialog">
        <h2>{title}</h2>
        <div className="pin-boxes">
          {digits.map((d, i) => (
            <div key={i} className={`pin-box${i === pos ? ' focused' : ''}`}>{i === pos ? d : typed[i] ? '●' : '–'}</div>
          ))}
        </div>
        {error && <div className="pin-error">{error}</div>}
        <div className="muted pin-help">Number keys, or ▲▼ to change a digit and OK to go on · BACK cancels</div>
      </div>
    </div>
  )
}
