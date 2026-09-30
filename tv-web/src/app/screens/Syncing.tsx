import type { SyncPhase } from '../controller'
import { Focusable } from '../Focusable'
import { setFocus } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect } from 'react'

/** Loading and failure both say something (CLAUDE.md): a stuck spinner is never the whole story. */
export function Syncing({ sync, onRetry }: { sync: Extract<SyncPhase, { kind: 'running' | 'error' }>; onRetry: () => void }) {
  useEffect(() => { if (sync.kind === 'error') void setFocus('sync-retry') }, [sync.kind])
  return (
    <div className="center-screen">
      {sync.kind === 'running' ? (
        <>
          <div className="spinner" />
          <h1>{sync.moved ? `You were moved to ${sync.provider}` : `Connecting to ${sync.provider}…`}</h1>
          <p>{sync.moved ? 'Loading the new channel list. Your old list has been cleared.' : 'Loading your channels.'}</p>
        </>
      ) : (
        <>
          <h1>Could not load {sync.provider}</h1>
          <p className="muted">{sync.message}</p>
          <div className="buttons"><Focusable focusKey="sync-retry" className="button" onEnter={onRetry}>Try again</Focusable></div>
        </>
      )}
    </div>
  )
}
