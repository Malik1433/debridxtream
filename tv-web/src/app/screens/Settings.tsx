import { useState } from 'react'
import type { LicenseState } from '../../license/policy'
import { thisTvCodecLine } from '../../player/codecSupport'
import { avplayAvailable } from '../../player/AvplayAdapter'
import { playerKindFor } from '../../player/PlayerAdapter'
import { deviceId, type Platform } from '../../platform'
import type { AppController, AppState } from '../controller'
import { Focusable } from '../Focusable'
import { PinDialog } from '../PinDialog'

declare const __APP_VERSION__: string

function licenceText(l: LicenseState): string {
  if (l.kind === 'locked') return `locked (${l.reason.replace('_', ' ')})`
  return l.trialDaysLeft !== null ? `trial, ${l.trialDaysLeft} day${l.trialDaysLeft === 1 ? '' : 's'} left` : `active (${l.tier})`
}

type PinAsk = null | 'choose' | 'confirm' | 'unlock' | 'disable'

export function Settings({ platform, state, controller, onSyncNow, onHome }:
  { platform: Platform; state: AppState; controller: AppController; onSyncNow: () => void; onHome: () => void }) {
  const id = deviceId(platform)
  const sync = state.sync
  const lib = state.library
  const p = controller.parental
  const [, refresh] = useState(0)
  const [ask, setAsk] = useState<PinAsk>(null)
  const [first, setFirst] = useState('')
  const [pinError, setPinError] = useState('')
  const close = () => { setAsk(null); setPinError(''); setFirst(''); refresh((n) => n + 1) }

  const onPin = (pin: string) => {
    if (ask === 'choose') { setFirst(pin); setAsk('confirm'); setPinError(''); return }
    // A new PIN is chosen in order to see adult categories: it also opens them for this session.
    if (ask === 'confirm') { if (pin === first && p.setPin(pin) && p.unlock(pin)) close(); else { setAsk('choose'); setPinError('The two PINs did not match. Start again.') } return }
    if (ask === 'unlock') { if (p.unlock(pin)) close(); else setPinError('Wrong PIN') ; return }
    if (ask === 'disable') { if (p.disable(pin)) close(); else setPinError('Wrong PIN') }
  }
  const parentalText = !p.enabled() ? 'off · adult categories shown' : p.unlocked() ? 'on · adult categories shown for this session' : 'on · adult categories hidden'

  return (
    <>
      <h1>Settings</h1>
      <div className="info">
        <div><b>TV code</b>{state.activationCode}</div>
        <div><b>Licence</b>{licenceText(state.license)}</div>
        <div><b>Account</b>{state.claimed ? 'linked to your phone' : 'not linked'}</div>
        <div><b>Provider</b>{state.providerName ?? '—'}</div>
        <div><b>Channels</b>{state.catalogue
          ? `${state.catalogue.channels.toLocaleString()} (updated ${new Date(state.catalogue.at).toLocaleTimeString()})` +
            (sync.kind === 'running' ? ' · updating…' : sync.kind === 'error' ? ` · update failed: ${sync.message}` : '')
          : sync.kind === 'running' ? 'updating…' : sync.kind === 'error' ? `update failed: ${sync.message}` : '—'}</div>
        <div><b>Movies / series</b>{lib ? `${lib.movies.toLocaleString()} / ${lib.shows.toLocaleString()}${lib.error ? ` · last update failed: ${lib.error}` : ''}` : '—'}{state.librarySyncing ? ' · updating…' : ''}</div>
        <div><b>Parental controls</b>{parentalText}</div>
        <div><b>Playback</b>{thisTvCodecLine()}</div>
        <div><b>Version</b>{__APP_VERSION__} · {platform} · {playerKindFor(platform)}{avplayAvailable() ? ' (+AVPlay)' : ''} · device {id ? `${id.slice(0, 6)}…` : 'n/a'}</div>
      </div>
      <div className="buttons" style={{ marginTop: 40 }}>
        <Focusable focusKey="settings-sync" className="button" onEnter={onSyncNow}>Update channels</Focusable>
        {!p.enabled() && <Focusable focusKey="settings-pin" className="button" onEnter={() => { p.turnOn(); refresh((n) => n + 1) }}>Hide adult categories</Focusable>}
        {p.enabled() && !p.unlocked() && <Focusable focusKey="settings-pin" className="button" onEnter={() => setAsk(p.hasPin() ? 'unlock' : 'choose')}>Show adult categories</Focusable>}
        {p.enabled() && p.unlocked() && <Focusable focusKey="settings-pin" className="button" onEnter={() => { p.lockNow(); refresh((n) => n + 1) }}>Hide adult categories now</Focusable>}
        {p.enabled() && p.hasPin() && <Focusable focusKey="settings-pin-off" className="button" onEnter={() => setAsk('disable')}>Turn protection off</Focusable>}
        <Focusable focusKey="settings-home" className="button" onEnter={onHome}>Back to Home</Focusable>
      </div>
      {ask && (
        <PinDialog platform={platform} error={pinError} onCancel={close} onDone={onPin}
          title={ask === 'choose' ? 'Choose a 4-digit PIN for adult categories' : ask === 'confirm' ? 'Enter the PIN again' : ask === 'unlock' ? 'PIN to show adult categories' : 'PIN to stop hiding adult categories'} />
      )}
    </>
  )
}
