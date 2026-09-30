import { Focusable } from '../Focusable'
import type { AppState } from '../controller'
import type { Screen } from '../router'

const TILES: Array<{ key: Screen; title: string; sub: string }> = [
  { key: 'live', title: 'Live TV', sub: 'Channels and guide' },
  { key: 'movies', title: 'Movies', sub: 'On demand' },
  { key: 'series', title: 'Series', sub: 'Seasons and episodes' },
  { key: 'settings', title: 'Settings', sub: 'Account and device' },
]

export function Home({ onOpen, state }: { onOpen: (s: Screen) => void; state: AppState }) {
  return (
    <>
      <h1>Welcome to DX Play</h1>
      <p>{state.sync.kind === 'done'
        ? `${state.providerName ?? 'Your provider'} · ${state.sync.channels.toLocaleString()} channels in ${state.sync.categories} categories`
        : state.sync.kind === 'running' ? `Refreshing ${state.sync.provider}…` : 'Pick a section with the arrows and press OK.'}</p>
      <div className="tiles">
        {TILES.map((t) => (
          <Focusable key={t.key} focusKey={`tile-${t.key}`} className="tile" onEnter={() => onOpen(t.key)}>
            <div className="t">{t.title}</div>
            <div className="s">{t.sub}</div>
          </Focusable>
        ))}
      </div>
    </>
  )
}
