import { setFocus } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect, useRef, useState } from 'react'
import type { LicenseState } from '../../../license/policy'
import { resumeLastLive, setResumeLastLive } from '../../../data/livePrefs'
import { thisTvCodecLine } from '../../../player/codecSupport'
import { learnedBad } from '../../../player/learnedAudio'
import { avplayAvailable } from '../../../player/AvplayAdapter'
import { playerKindFor } from '../../../player/PlayerAdapter'
import { deviceId, type Platform } from '../../../platform'
import type { AppController, AppState } from '../../controller'
import { Focusable } from '../../Focusable'
import { Icon } from '../../icons'
import { PinDialog } from '../../PinDialog'
import { ManageOnPhone } from './ManageOnPhone'
import { CATEGORIES, isFocusableRow, type CategoryKey, type Row } from './settingsModel'
import { useParentalFlow } from './useParentalFlow'

declare const __APP_VERSION__: string

function licenceText(l: LicenseState): string {
  if (l.kind === 'locked') return `Locked (${l.reason.replace('_', ' ')})`
  return l.trialDaysLeft !== null ? `Trial · ${l.trialDaysLeft} day${l.trialDaysLeft === 1 ? '' : 's'} left` : `Active (${l.tier})`
}

/**
 * Android `fragment_settings_v2`: the category rail on the left (⚙ Settings, the version, then the
 * categories with their accent tile, bar and caption) and the panel on the right (dot, title,
 * description, rows). Moving along the rail opens each category, as Movies' categories do.
 */
export function SettingsScreen({ platform, state, controller, onSyncNow }: {
  platform: Platform; state: AppState; controller: AppController; onSyncNow: () => void
}) {
  const [cat, setCat] = useState(0)
  const [, refresh] = useState(0)
  const [qr, setQr] = useState(false)
  const parental = useParentalFlow(controller.parental)
  // W4 QA R1: as on Android (SettingsCategoryAdapter's click), OK opens a category and takes focus to
  // its first row; moving along the rail only moves focus.
  const toPanel = useRef(false)
  const list = useRef<HTMLDivElement>(null)
  useEffect(() => { void setFocus('set-cat-0') }, [])

  const c = CATEGORIES[cat]
  const rows = rowsFor(c.key)
  useEffect(() => {
    if (!toPanel.current) return
    toPanel.current = false
    const first = rows.find(isFocusableRow)
    if (first) void setFocus(`set-row-${first.key}`)
  })

  function rowsFor(key: CategoryKey): Row[] {
    const sync = state.sync, lib = state.library
    switch (key) {
      case 'playback': return [
        { kind: 'info', key: 'codecs', title: 'This TV plays', value: thisTvCodecLine(learnedBad(localStorage), avplayAvailable() ? 'Samsung player' : null), icon: 'play' },
        { kind: 'info', key: 'player', title: 'Player', value: `${playerKindFor(platform)}${avplayAvailable() ? ' (+AVPlay)' : ''}`, icon: 'play' },
      ]
      case 'live': {
        const on = resumeLastLive(localStorage)
        return [{
          kind: 'toggle', key: 'resume_last_live', title: 'Resume Last Channel', on, icon: 'live_tv',
          desc: 'Open Live TV on the channel you watched last instead of the first',
          onPress: () => { setResumeLastLive(localStorage, !on); refresh((n) => n + 1) },
        }]
      }
      case 'data': return [
        {
          kind: 'action', key: 'refresh_iptv', title: 'Refresh Channels & Catalog', icon: 'settings',
          desc: sync.kind === 'running' || state.librarySyncing ? 'Updating…'
            : sync.kind === 'error' ? `Last update failed: ${sync.message}`
            : 'Fetch live channels, movies and series from the provider again',
          onPress: () => { onSyncNow(); controller.retryLibrary() },
        },
        { kind: 'info', key: 'channels', title: 'Channels', icon: 'live_tv',
          value: state.catalogue ? `${state.catalogue.channels.toLocaleString()} · ${new Date(state.catalogue.at).toLocaleTimeString()}` : '—' },
        { kind: 'info', key: 'library', title: 'Movies / Series', icon: 'movie',
          value: lib ? `${lib.movies.toLocaleString()} / ${lib.shows.toLocaleString()}${lib.error ? ' · last update failed' : ''}` : '—' },
      ]
      case 'about': {
        const id = deviceId(platform)
        return [
          { kind: 'info', key: 'version', title: 'App Version', value: __APP_VERSION__, icon: 'person' },
          { kind: 'info', key: 'build', title: 'Build', value: platform, icon: 'person' },
          { kind: 'info', key: 'device', title: 'Device', value: id ? `${id.slice(0, 6)}…` : 'n/a', icon: 'person' },
        ]
      }
      case 'account': return [
        { kind: 'info', key: 'account_user', title: 'Signed in as', value: controller.accountUser() ?? 'Not signed in', icon: 'person' },
        { kind: 'info', key: 'account_server', title: 'Provider', value: state.providerName ?? '—', icon: 'person' },
        { kind: 'info', key: 'tv_code', title: 'TV code', value: state.activationCode, icon: 'person' },
        { kind: 'info', key: 'licence', title: 'Licence', value: licenceText(state.license), icon: 'person' },
        {
          kind: 'action', key: 'manage_on_phone', title: 'Manage on your phone', icon: 'person',
          desc: state.claimed ? 'Linked to your phone · scan to add or change playlists' : 'Scan a code to add or change playlists and add-ons',
          onPress: () => setQr(true),
        },
        ...parental.rows,
      ]
    }
  }

  /** Keep the focused row inside the panel (Android's RecyclerView scrolls the same way). */
  const reveal = (key: string) => {
    const box = list.current, el = box?.querySelector<HTMLElement>(`[data-row="${key}"]`)
    if (!box || !el) return
    const top = el.offsetTop, bottom = top + el.offsetHeight
    // The first row focus can reach brings the info rows above it back into view.
    const firstFocusable = rows.find(isFocusableRow)?.key === key
    if (firstFocusable && bottom <= box.clientHeight) box.scrollTop = 0
    else if (top < box.scrollTop) box.scrollTop = top
    else if (bottom > box.scrollTop + box.clientHeight) box.scrollTop = bottom - box.clientHeight
  }

  return (
    <div className="set2">
      <div className="set2-rail">
        <div className="set2-brand"><Icon name="settings" size={44} /><h1>Settings</h1></div>
        <div className="set2-version">DX PLAY · v{__APP_VERSION__}</div>
        {CATEGORIES.map((k, i) => (
          <Focusable key={k.key} focusKey={`set-cat-${i}`} className={`set2-cat${i === cat ? ' on' : ''}`}
            onEnter={() => { toPanel.current = true; if (i === cat) refresh((n) => n + 1); else setCat(i) }}>
            <span className="set2-cat-bar" style={{ background: k.accent }} />
            <span className="set2-cat-icon" style={{ color: i === cat ? k.accent : '#64748B', background: `${k.accent}${i === cat ? '1A' : '0A'}`, borderColor: `${k.accent}${i === cat ? '33' : '10'}` }}>
              <Icon name={k.icon} size={40} />
            </span>
            <span className="set2-cat-text"><span className="set2-cat-t">{k.title}</span><span className="set2-cat-s">{k.sub}</span></span>
            <Icon name="chevron_left" size={34} className="set2-cat-arrow" />
          </Focusable>
        ))}
      </div>
      <div className="set2-divider" />
      <div className="set2-panel">
        <div className="set2-head"><span className="set2-dot" style={{ background: c.accent, boxShadow: `0 0 12px ${c.accent}` }} />{c.title}</div>
        <div className="set2-desc">{c.desc}</div>
        <div className="set2-rows" ref={list}>
          {rows.map((r) => <SettingRow key={r.key} r={r} accent={c.accent} onFocus={reveal} />)}
        </div>
      </div>
      {parental.dialog && <PinDialog platform={platform} {...parental.dialog} />}
      {qr && <ManageOnPhone code={state.activationCode} onClose={() => { setQr(false); void setFocus('set-row-manage_on_phone') }} />}
    </div>
  )
}

function SettingRow({ r, accent, onFocus }: { r: Row; accent: string; onFocus: (key: string) => void }) {
  const a = r.kind === 'action' && r.destructive ? '#FF3366' : accent
  const body = (
    <>
      <span className="set2-icon" style={{ color: a, background: `${a}14`, borderColor: `${a}2E` }}><Icon name={r.icon} size={44} /></span>
      <span className="set2-text">
        <span className="set2-title" style={r.kind === 'action' && r.destructive ? { color: a } : undefined}>{r.title}</span>
        {r.kind !== 'info' && r.desc && <span className="set2-sub">{r.desc}</span>}
      </span>
      {r.kind === 'info' && <span className="set2-value">{r.value}</span>}
      {r.kind === 'toggle' && (
        <span className={`set2-pill${r.on ? ' on' : ''}`} style={r.on ? { background: `${a}40`, borderColor: `${a}66` } : undefined}>
          <i style={r.on ? { background: a } : undefined} />
        </span>
      )}
      {r.kind === 'action' && <Icon name="chevron_left" size={30} className="set2-chevron" />}
    </>
  )
  if (!isFocusableRow(r)) return <div className="set2-row info" data-row={r.key}>{body}</div>
  return (
    <div data-row={r.key}>
      <Focusable focusKey={`set-row-${r.key}`} className={`set2-row ${r.kind}`} onEnter={r.onPress} onFocus={() => onFocus(r.key)}>{body}</Focusable>
    </div>
  )
}
