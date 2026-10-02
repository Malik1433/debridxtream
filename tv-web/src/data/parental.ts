import { sha256Hex } from '../sha256'
import type { KeyValue } from './session'

/**
 * Parental controls - a line-for-line port of Android's AdultContentDetector + ParentalPolicy
 * (2026-09-03), so a household gets the same answer on the phone, the Fire TV and the Samsung.
 *
 * - Xtream has no adult flag: the category NAME is the only signal, matched on whole tokens.
 * - Hidden means hidden everywhere (lists, search, Home rows).
 * - A correct PIN opens adult categories for 30 minutes of this session; a restart locks again.
 * - The PIN is never stored: a random salt and SHA-256(salt + ":" + pin).
 * Device-level, not server-scoped: the household's rule does not change with the provider.
 */
const ADULT_TOKENS = new Set(['xxx', 'adult', 'adults', 'porn', 'porno', 'erotic', 'erotik', 'erotica', 'erotico',
  'erotique', '18+', '+18', '18', 'hentai', 'sex', 'sexy', 'nsfw', 'erwachsene', 'adultos', 'adulto', 'adulte', 'adultes', 'adulti'])
const WEAK_TOKENS = new Set(['sex', 'sexy', '18'])
const SPLIT = /[^\p{L}\p{N}+]+/u

export function isAdultCategoryName(name: string | null | undefined): boolean {
  const raw = (name ?? '').trim()
  if (!raw) return false
  if (raw.includes('\u{1F51E}')) return true
  const tokens = raw.toLowerCase().split(SPLIT).filter(Boolean)
  if (tokens.some((t) => ADULT_TOKENS.has(t) && !WEAK_TOKENS.has(t))) return true
  if (tokens.some((t) => t === '18+' || t === '+18')) return true
  return tokens.some((t, i) => t === '18' && (tokens[i + 1] === 'plus' || tokens[i + 1] === 'only'))
}

export const PIN_LENGTH = 4
export const SESSION_UNLOCK_MS = 30 * 60 * 1000
const KEY = 'dx.parental'

interface Stored { enabled: boolean; salt: string; hash: string }

export const isValidPin = (pin: string) => pin.length === PIN_LENGTH && /^[0-9]+$/.test(pin)
export const hashPin = (salt: string, pin: string) => sha256Hex(`${salt}:${pin}`)

function newSalt(): string {
  const b = new Uint8Array(16)
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) crypto.getRandomValues(b)
  else for (let i = 0; i < b.length; i++) b[i] = Math.floor(Math.random() * 256)
  return Array.from(b, (x) => (x < 16 ? '0' : '') + x.toString(16)).join('')
}

/** Constant-time compare: a wrong PIN and a right one take the same time. */
function same(a: string, b: string): boolean {
  if (a.length !== b.length) return false
  let d = 0
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return d === 0
}

export class Parental {
  private unlockedUntil = 0
  constructor(private readonly kv: Pick<KeyValue, 'getItem' | 'setItem' | 'removeItem'>, private readonly now: () => number = Date.now) {}

  private stored(): Stored | null {
    try { const v = JSON.parse(this.kv.getItem(KEY) ?? 'null'); return v && v.salt && v.hash ? v : null } catch { return null }
  }

  hasPin(): boolean { return this.stored() !== null }
  /** On only with a PIN - the same rule as Android (ParentalControls.isEnabled). */
  enabled(): boolean { return Boolean(this.stored()?.enabled) }
  unlocked(): boolean { return this.now() < this.unlockedUntil }
  hidesAdult(): boolean { return this.enabled() && !this.unlocked() }

  /** Turn controls on with a new PIN. */
  enable(pin: string): boolean {
    if (!isValidPin(pin)) return false
    const salt = newSalt()
    this.kv.setItem(KEY, JSON.stringify({ enabled: true, salt, hash: hashPin(salt, pin) }))
    this.unlockedUntil = 0
    return true
  }

  matches(pin: string): boolean {
    const s = this.stored()
    return Boolean(s && isValidPin(pin) && same(hashPin(s.salt, pin), s.hash))
  }

  unlock(pin: string): boolean {
    if (!this.matches(pin)) return false
    this.unlockedUntil = this.now() + SESSION_UNLOCK_MS
    return true
  }

  lockNow(): void { this.unlockedUntil = 0 }

  /** Turning controls off needs the PIN, or it would be no lock at all. */
  disable(pin: string): boolean {
    if (!this.matches(pin)) return false
    this.kv.removeItem(KEY)
    this.unlockedUntil = 0
    return true
  }

  /** Remove adult categories, and the items that belong to them. */
  filterCategories<T extends { name: string }>(cats: T[]): T[] {
    return this.hidesAdult() ? cats.filter((c) => !isAdultCategoryName(c.name)) : cats
  }

  filterItems<T extends { categoryId: string }>(items: T[], cats: Array<{ id: string; name: string }>): T[] {
    if (!this.hidesAdult()) return items
    const adult = new Set(cats.filter((c) => isAdultCategoryName(c.name)).map((c) => c.id))
    return adult.size ? items.filter((i) => !adult.has(i.categoryId)) : items
  }
}
