/**
 * The catalogue sync's scheduling, pulled out of AppController so it can be tested without Firebase
 * (W2 QA, 2026-09-30, found both rules on the device; this keeps them from coming back):
 *
 *  - D2, never drop a request. A switch arrives from Firestore while the launch refresh is still in
 *    flight; dropping it left the TV on the new provider's NAME with the old provider's channels.
 *    A request made while a sync runs is queued, and a queued "moved" is never downgraded.
 *  - D4, a result from before a move is never published. [moved] bumps the generation; a sync that
 *    started under an older generation - success or failure - is thrown away and run again, as a move.
 */
export interface SyncEvents<R> {
  started(moved: boolean): void
  succeeded(result: R): void
  failed(error: unknown): void
}

export class SyncRunner<R> {
  private running = false
  private queued: boolean | null = null
  private generation = 0

  constructor(private readonly sync: () => Promise<R>, private readonly events: SyncEvents<R>) {}

  /** The provider changed: anything in flight now belongs to the previous one. */
  moved(): void { this.generation++ }

  get isRunning(): boolean { return this.running }

  async request(moved: boolean): Promise<void> {
    if (this.running) { this.queued = (this.queued ?? false) || moved; return }
    this.running = true
    let run = moved
    try {
      do {
        this.queued = null
        this.events.started(run)
        const gen = this.generation
        try {
          const r = await this.sync()
          if (gen !== this.generation) { run = true; this.queued ??= true; continue }
          this.events.succeeded(r)
        } catch (e) {
          if (gen !== this.generation) { run = true; this.queued ??= true; continue }
          this.events.failed(e)
        }
        run = this.queued ?? false
      } while (this.queued !== null)
    } finally {
      this.running = false
      this.queued = null
    }
  }
}
