import { useMemo } from 'react'
import type { WatchEntry } from '../../data/watchState'
import { Focusable } from '../Focusable'
import type { AppController, AppState } from '../controller'
import type { Screen } from '../router'
import { Poster } from './library/Poster'

const TILES: Array<{ key: Screen; title: string; sub: string }> = [
  { key: 'live', title: 'Live TV', sub: 'Channels and guide' },
  { key: 'movies', title: 'Movies', sub: 'On demand' },
  { key: 'series', title: 'Series', sub: 'Seasons and episodes' },
  { key: 'search', title: 'Search', sub: 'Channels, films, series' },
]

/** Sections first (always reachable), then "Continue watching" - the row a returning viewer wants. */
export function Home({ onOpen, onContinue, onChannel, state, controller }: {
  onOpen: (s: Screen) => void; onContinue: (e: WatchEntry) => void; onChannel: (id: string) => void; state: AppState; controller: AppController
}) {
  const cont = useMemo(() => controller.continueWatching(), [controller])
  const recent = useMemo(() => controller.recentChannels(), [controller])
  const lib = state.library
  return (
    <>
      <h1>Welcome to DX Play</h1>
      {/* The catalogue we hold, not the refresh in flight: a refresh must not blank the line. */}
      <p>{state.catalogue
        ? `${state.providerName ?? 'Your provider'} · ${state.catalogue.channels.toLocaleString()} channels` +
          (lib ? ` · ${lib.movies.toLocaleString()} movies · ${lib.shows.toLocaleString()} series` : state.librarySyncing ? ' · loading movies and series…' : '') +
          (state.sync.kind === 'running' ? ' · refreshing…' : '')
        : state.sync.kind === 'running' ? `Refreshing ${state.sync.provider}…` : 'Pick a section with the arrows and press OK.'}</p>
      <div className="tiles">
        {TILES.map((t) => (
          <Focusable key={t.key} focusKey={`tile-${t.key}`} className="tile" onEnter={() => onOpen(t.key)}>
            <div className="t">{t.title}</div>
            <div className="s">{t.sub}</div>
          </Focusable>
        ))}
      </div>
      {recent.length > 0 && (
        <>
          <h2 className="home-row-title">Recently watched channels</h2>
          <div className="sr-row">
            {recent.slice(0, 8).map((c, i) => (
              <Focusable key={c.id} focusKey={`recent-${i}`} className="sr-chan" onEnter={() => onChannel(c.id)}>
                {c.icon ? <img src={c.icon} alt="" onError={(e) => { e.currentTarget.style.visibility = 'hidden' }} /> : null}<span>{c.name}</span>
              </Focusable>
            ))}
          </div>
        </>
      )}
      {cont.length > 0 && (
        <>
          <h2 className="home-row-title">Continue watching</h2>
          <div className="sr-row">
            {cont.slice(0, 8).map((e, i) => (
              <Focusable key={`${e.kind}-${e.id}`} focusKey={`cont-${i}`} className="sr-poster" onEnter={() => onContinue(e)}>
                <Poster src={e.poster} title={e.seriesName ?? e.title} sub={e.kind === 'episode' ? `S${e.season} · E${e.episode}` : undefined}
                  progress={e.durationMs ? e.progressMs / e.durationMs : 0} />
              </Focusable>
            ))}
          </div>
        </>
      )}
    </>
  )
}
