import type { LicenseState } from '../../license/policy'
import { playerKindFor } from '../../player/PlayerAdapter'
import { deviceId, type Platform } from '../../platform'
import type { AppState } from '../controller'
import { Focusable } from '../Focusable'

declare const __APP_VERSION__: string

function licenceText(l: LicenseState): string {
  if (l.kind === 'locked') return `locked (${l.reason.replace('_', ' ')})`
  return l.trialDaysLeft !== null ? `trial, ${l.trialDaysLeft} day${l.trialDaysLeft === 1 ? '' : 's'} left` : `active (${l.tier})`
}

export function Settings({ platform, state, onSyncNow, onHome }:
  { platform: Platform; state: AppState; onSyncNow: () => void; onHome: () => void }) {
  const id = deviceId(platform)
  const sync = state.sync
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
        <div><b>Version</b>{__APP_VERSION__} · {platform} · {playerKindFor(platform)} · device {id ? `${id.slice(0, 6)}…` : 'n/a'}</div>
      </div>
      <div className="buttons" style={{ marginTop: 40 }}>
        <Focusable focusKey="settings-sync" className="button" onEnter={onSyncNow}>Update channels</Focusable>
        <Focusable focusKey="settings-home" className="button" onEnter={onHome}>Back to Home</Focusable>
      </div>
    </>
  )
}
