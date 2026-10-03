import { useState } from 'react'
import type { Parental } from '../../../data/parental'
import type { Row } from './settingsModel'

/** Which PIN the dialog is asking for, and what the new one is for. */
type Ask = null | 'current' | 'choose' | 'confirm' | 'unlock' | 'disable'
type Goal = 'change' | 'unlock' | 'off'

const TITLES: Record<Exclude<Ask, null>, string> = {
  current: 'Enter your current PIN',
  choose: 'Choose a 4-digit PIN',
  confirm: 'Enter the PIN again',
  unlock: 'PIN to show adult categories',
  disable: 'PIN to turn parental controls off',
}

/**
 * Android SettingsParentalRows: the "Parental controls" toggle, then Change PIN, then "Show adult
 * categories for this session" or "Hide adult categories now". Every way OFF needs the PIN;
 * every way ON needs none.
 */
export function useParentalFlow(p: Parental) {
  const [ask, setAsk] = useState<Ask>(null)
  const [goal, setGoal] = useState<Goal>('change')
  const [first, setFirst] = useState('')
  const [error, setError] = useState('')
  const [, refresh] = useState(0)
  const close = () => { setAsk(null); setError(''); setFirst(''); refresh((n) => n + 1) }
  const start = (a: Ask, g: Goal = goal) => { setGoal(g); setError(''); setAsk(a) }

  const onPin = (pin: string) => {
    if (ask === 'current') { if (p.matches(pin)) { setError(''); setAsk('choose') } else setError('Wrong PIN'); return }
    if (ask === 'choose') { setFirst(pin); setError(''); setAsk('confirm'); return }
    if (ask === 'confirm') {
      if (pin !== first || !p.setPin(pin)) { setFirst(''); setAsk('choose'); setError('The two PINs did not match. Start again.'); return }
      if (goal === 'unlock') p.unlock(pin)
      if (goal === 'off') p.disable(pin)
      close(); return
    }
    if (ask === 'unlock') { if (p.unlock(pin)) close(); else setError('Wrong PIN'); return }
    if (ask === 'disable') { if (p.disable(pin)) close(); else setError('Wrong PIN') }
  }

  const on = p.enabled()
  const rows: Row[] = [
    {
      kind: 'toggle', key: 'parental_enabled', title: 'Parental controls', icon: 'person', on,
      desc: 'Hide adult categories everywhere until the PIN is entered',
      onPress: () => {
        if (!on) { p.turnOn(); refresh((n) => n + 1) }
        else if (p.hasPin()) start('disable', 'off')
        else start('choose', 'off')
      },
    },
  ]
  if (on) rows.push({
    kind: 'action', key: 'parental_change_pin', title: 'Change PIN', icon: 'person',
    desc: 'Four digits. Needed to unlock adult categories or turn this off',
    onPress: () => start(p.hasPin() ? 'current' : 'choose', 'change'),
  })
  if (on && !p.unlocked()) rows.push({
    kind: 'action', key: 'parental_unlock', title: 'Show adult categories for this session', icon: 'person',
    desc: 'Enter the PIN. They hide again after 30 minutes or when the app closes',
    onPress: () => start(p.hasPin() ? 'unlock' : 'choose', 'unlock'),
  })
  if (on && p.unlocked()) rows.push({
    kind: 'action', key: 'parental_lock', title: 'Hide adult categories now', icon: 'person',
    desc: 'Unlocked for this session', onPress: () => { p.lockNow(); refresh((n) => n + 1) },
  })

  return { rows, dialog: ask ? { title: TITLES[ask], error, onDone: onPin, onCancel: close } : null }
}
