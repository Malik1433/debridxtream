/**
 * The screen stack. BACK goes up the hierarchy and never traps the viewer (CLAUDE.md, TV rulebook):
 * at the root it asks to leave instead of doing nothing. Pure, so it is unit-tested.
 */
export type Screen = 'home' | 'live' | 'movies' | 'series' | 'search' | 'settings'

export class Router {
  private stack: Screen[] = ['home']

  get current(): Screen {
    return this.stack[this.stack.length - 1]
  }

  get atRoot(): boolean {
    return this.stack.length === 1
  }

  /** Opening a top-level section replaces whatever section was open: home -> section, never deeper. */
  open(screen: Screen): void {
    this.stack = screen === 'home' ? ['home'] : ['home', screen]
  }

  /** One level ABOVE the current screen (Search opened from Movies): BACK returns to it, as on Android. */
  push(screen: Screen): void {
    if (this.current !== screen) this.stack.push(screen)
  }

  /** @return false when there is nothing to go back to (the caller then offers to exit). */
  back(): boolean {
    if (this.atRoot) return false
    this.stack.pop()
    return true
  }
}
