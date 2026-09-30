import type { LicenseState } from '../license/policy'
import type { AppState, SyncPhase } from './controller'

/** Which screen the app owes the viewer right now, carrying what that screen needs. */
export type Gate =
  | { screen: 'activation'; reason: Extract<LicenseState, { kind: 'locked' }>['reason'] }
  | { screen: 'setup' }
  | { screen: 'syncing'; sync: Extract<SyncPhase, { kind: 'running' | 'error' }> }
  | { screen: 'app' }

/**
 * The gate, in order: a licence -> an account to play from -> a first catalogue -> the app.
 *
 * The rule that matters is the last one: once a catalogue exists it is shown, and a refresh -
 * running, failed, or asked for by hand from Settings - never takes the screen back. W2 QA
 * (2026-09-30) found the opposite, because the gate read the in-flight `sync`, which returns to
 * 'running' on every refresh: pressing "Update channels" threw Settings back to Home, and every
 * relaunch of a fully synced TV sat on "Loading channels" for 30 s before showing anything.
 */
export function gateFor(s: AppState): Gate {
  if (s.license.kind === 'locked') return { screen: 'activation', reason: s.license.reason }
  if (!s.hasAccount) return { screen: 'setup' }
  const hasCatalogue = s.catalogue !== null && s.catalogue.channels > 0
  if (!hasCatalogue && (s.sync.kind === 'running' || s.sync.kind === 'error')) {
    return { screen: 'syncing', sync: s.sync }
  }
  return { screen: 'app' }
}
