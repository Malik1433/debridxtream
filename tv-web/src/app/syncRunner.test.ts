import { describe, expect, it } from 'vitest'
import { SyncRunner } from './syncRunner'

/** A sync whose calls we finish by hand, in order. */
function manualSync() {
  const calls: Array<{ resolve: (v: string) => void; reject: (e: unknown) => void }> = []
  const sync = () => new Promise<string>((resolve, reject) => { calls.push({ resolve, reject }) })
  return { calls, sync }
}
const tick = () => new Promise((r) => setTimeout(r, 0))

function harness() {
  const m = manualSync()
  const log: string[] = []
  const runner = new SyncRunner(m.sync, {
    started: (moved) => log.push(`start${moved ? ' moved' : ''}`),
    succeeded: (r) => log.push(`ok ${r}`),
    failed: (e) => log.push(`fail ${String(e)}`),
  })
  return { ...m, log, runner }
}

describe('SyncRunner', () => {
  it('D2: a request during a sync is queued, not dropped, and a queued move stays a move', async () => {
    const h = harness()
    const done = h.runner.request(false)
    await tick()
    void h.runner.request(false)
    void h.runner.request(true)
    void h.runner.request(false)
    h.calls[0].resolve('A'); await tick()
    h.calls[1].resolve('B'); await done
    expect(h.log).toEqual(['start', 'ok A', 'start moved', 'ok B'])
    expect(h.runner.isRunning).toBe(false)
  })

  it('D4: a result that started before a move is not published, and the sync reruns as a move', async () => {
    const h = harness()
    const done = h.runner.request(false)
    await tick()
    h.runner.moved()
    h.calls[0].resolve('OLD provider'); await tick()
    h.calls[1].resolve('NEW provider'); await done
    expect(h.log).toEqual(['start', 'start moved', 'ok NEW provider'])
  })

  it('D4: a failure from before a move is not shown either', async () => {
    const h = harness()
    const done = h.runner.request(false)
    await tick()
    h.runner.moved()
    h.calls[0].reject('old server down'); await tick()
    h.calls[1].resolve('NEW'); await done
    expect(h.log).toEqual(['start', 'start moved', 'ok NEW'])
  })

  it('reports a real failure, and runs again on the next request', async () => {
    const h = harness()
    let done = h.runner.request(false)
    await tick()
    h.calls[0].reject('timeout'); await done
    done = h.runner.request(false)
    await tick()
    h.calls[1].resolve('X'); await done
    expect(h.log).toEqual(['start', 'fail timeout', 'start', 'ok X'])
  })
})
