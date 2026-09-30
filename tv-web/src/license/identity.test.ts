import { createHash } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { sha256Hex } from '../sha256'
import { serverFingerprint, NO_SERVER } from '../data/serverIdentity'
import { deriveActivationCode, identityProofFor, installIdFor, tvIdOrInstallSecret } from './identity'

describe('sha256', () => {
  it('matches the reference implementation, including non-ASCII and multi-block input', () => {
    for (const s of ['', 'abc', 'x'.repeat(55), 'y'.repeat(64), 'z'.repeat(130), 'Überkanal – ✓ 𝄞']) {
      expect(sha256Hex(s)).toBe(createHash('sha256').update(s, 'utf8').digest('hex'))
    }
  })
})

describe('licence identity', () => {
  it('derives the SAME activation codes as Android (DeviceIdentityTest vectors)', () => {
    expect(deriveActivationCode('hw-0123456789abcdef0123456789abcdef')).toBe('F6ZW-B42U')
    expect(deriveActivationCode('3f2b8c1e-1111-4222-8333-944455556666')).toBe('7GGS-LT82')
  })
  it('keeps each platform apart and fits the rules (activation alphabet, 64-hex proof)', () => {
    const samsung = installIdFor('tizen', 'K7G7MR')
    expect(samsung).toMatch(/^tv-[0-9a-f]{32}$/)
    expect(installIdFor('vidaa', 'K7G7MR')).not.toBe(samsung)
    expect(deriveActivationCode(samsung)).toMatch(/^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/)
    expect(identityProofFor('tizen', 'K7G7MR')).toMatch(/^[a-f0-9]{64}$/)
    expect(identityProofFor('tizen', 'K7G7MR')).not.toBe(samsung.slice(3))
  })
  it('falls back to one random secret per install, kept', () => {
    const store = new Map<string, string>()
    const s = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => { store.set(k, v) } }
    const first = tvIdOrInstallSecret(null, s)
    expect(tvIdOrInstallSecret(null, s)).toBe(first)
    expect(tvIdOrInstallSecret('HW', s)).toBe('HW')
  })
})

describe('serverFingerprint (port of ServerIdentity)', () => {
  it('reads one provider written several ways as one, and a different account as another', () => {
    const canonical = serverFingerprint('http://tv.example', 'user1')
    expect(canonical).toMatch(/^[0-9a-f]{16}$/)
    for (const u of ['https://tv.example', 'http://tv.example/', 'http://TV.Example', '  http://tv.example  ', 'tv.example', 'http://a:b@tv.example/x?y'])
      expect(serverFingerprint(u, 'user1')).toBe(canonical)
    expect(serverFingerprint('http://tv.example', 'User1')).not.toBe(canonical)
    expect(serverFingerprint('http://tv.example:8080', 'user1')).not.toBe(canonical)
    expect(serverFingerprint('', 'user1')).toBe(NO_SERVER)
    expect(serverFingerprint('http://tv.example', ' ')).toBe(NO_SERVER)
  })
  it('equals the Android digest byte for byte', () => {
    const expected = createHash('sha256').update('tv.example|user1', 'utf8').digest('hex').slice(0, 16)
    expect(serverFingerprint('http://tv.example', 'user1')).toBe(expected)
  })
})
