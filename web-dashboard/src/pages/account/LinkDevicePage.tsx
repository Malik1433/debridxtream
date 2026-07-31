import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { httpsCallable } from 'firebase/functions'
import { ArrowLeft, Check, Tv } from 'lucide-react'
import { functions } from '../../firebase'
import { useAccount } from '../../account/useAccount'
import { verificationGrace } from '../../account/verificationGrace'
import { DxLogo } from '../reseller/authUi'
import { Field } from './accountUi'

/**
 * Device keys are XXXX-XXXX (the TV's permanent activation code). Accept them typed with or without
 * the dash, in any case — someone reading them off a television and typing on a phone should not be
 * defeated by punctuation.
 */
function normalizeDeviceKey(raw: string): string {
    const compact = raw.toUpperCase().replace(/[^A-Z0-9]/g, '')
    if (compact.length === 8) return `${compact.slice(0, 4)}-${compact.slice(4)}`
    return compact
}

interface ClaimResult { installId: string; identityPending: boolean }

/** Turns a callable error into something a customer can act on. */
function claimError(err: unknown): string {
    const code = ((err as { code?: string })?.code || '').replace('functions/', '')
    const message = (err as { message?: string })?.message
    switch (code) {
        case 'not-found':
            return 'No TV found for that code. Check the code on your TV screen and try again.'
        case 'permission-denied':
            return 'That TV is already linked to a different account.'
        case 'failed-precondition':
        case 'resource-exhausted':
            // These carry a specific, already-friendly message from the function.
            return message || 'That did not work. Please contact your provider.'
        case 'unauthenticated':
            return 'Please sign in again.'
        default:
            return message || 'Something went wrong. Please try again.'
    }
}

export default function LinkDevicePage() {
    const nav = useNavigate()
    const { search } = useLocation()
    const [params] = useSearchParams()
    const { user, profile, loading } = useAccount()

    const [code, setCode] = useState(() => normalizeDeviceKey(params.get('code') || ''))
    const [deviceName, setDeviceName] = useState('')
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')
    const [done, setDone] = useState<ClaimResult | null>(null)

    // Arriving from the TV's QR while signed out is the NORMAL path, not an error. Bounce to
    // sign-in carrying BOTH the code and where we were going — carrying only the code lands the
    // customer back on the account page holding a code with nothing to do with it.
    useEffect(() => {
        if (loading || user) return
        const params = new URLSearchParams(search)
        if (!params.get('next')) params.set('next', '/link')
        nav(`/account/login?${params.toString()}`, { replace: true })
    }, [loading, user, nav, search])

    if (loading || !user) {
        return <div className="mod" style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', opacity: 0.5 }}>Loading…</div>
    }

    const createdAt = (profile?.createdAt as unknown as { toMillis?: () => number })?.toMillis?.()
    const grace = verificationGrace(Date.now(), user.emailVerified, createdAt)

    async function claim(e: React.FormEvent) {
        e.preventDefault()
        const key = normalizeDeviceKey(code)
        if (key.length < 6) { setError('Enter the code shown on your TV.'); return }
        setError(''); setBusy(true)
        try {
            const fn = httpsCallable<{ activationCode: string; deviceName: string }, ClaimResult>(functions, 'claimDevice')
            const res = await fn({ activationCode: key, deviceName: deviceName.trim() })
            setDone(res.data)
        } catch (err: unknown) {
            setError(claimError(err))
        } finally { setBusy(false) }
    }

    return (
        <div className="mod" style={{ minHeight: '100vh' }}>
            <div style={{ borderBottom: '1px solid var(--color-neutral-200)', padding: '16px 24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <DxLogo />
                <button onClick={() => nav('/account')} className="btn btn-ghost" style={{ fontSize: 13, opacity: 0.6, display: 'flex', alignItems: 'center', gap: 6 }}><ArrowLeft size={14} />Account</button>
            </div>

            <div style={{ maxWidth: 520, margin: '0 auto', padding: '32px 24px' }}>
                {done ? (
                    <div className="card" style={{ padding: 32, textAlign: 'center' }}>
                        <div style={{ width: 56, height: 56, background: 'var(--color-accent-100)', display: 'grid', placeItems: 'center', margin: '0 auto 16px' }}>
                            <Check size={26} color="#B45309" strokeWidth={3} />
                        </div>
                        <h2 style={{ margin: '0 0 6px' }}>TV added</h2>
                        <p style={{ margin: 0, fontSize: 14, opacity: 0.7 }}>
                            {done.identityPending
                                // The TV has not signed in yet, so its playlist link is deferred. Say what to
                                // do rather than leaving them wondering why nothing appeared.
                                ? 'Restart the app on your TV once — it will pick up your playlists after that.'
                                : 'Your playlists will appear on it automatically.'}
                        </p>
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 22 }}>
                            <button onClick={() => nav('/account/playlists')} className="btn btn-primary btn-block" style={{ justifyContent: 'center', padding: '11px 16px' }}>Set up my playlists</button>
                            <button onClick={() => nav('/account')} className="btn btn-ghost" style={{ opacity: 0.6 }}>Back to my account</button>
                        </div>
                    </div>
                ) : (
                    <>
                        <div className="kicker" style={{ marginBottom: 8 }}>My Account</div>
                        <h2 style={{ margin: '0 0 4px' }}>Add this TV</h2>
                        <p style={{ margin: '0 0 24px', fontSize: 14, opacity: 0.6 }}>
                            Enter the code shown on your TV screen to link it to your account.
                        </p>

                        {!grace.allowed && (
                            <div className="card" style={{ marginBottom: 20, padding: '14px 18px', fontSize: 14, background: 'var(--color-accent-100)', borderColor: 'var(--color-accent-300)' }}>
                                <b>Verify your email to add a TV.</b>
                                <div style={{ marginTop: 10 }}><button onClick={() => nav('/account/verify')} className="btn btn-secondary" style={{ fontSize: 13 }}>Verify now</button></div>
                            </div>
                        )}

                        <form onSubmit={claim} className="card" style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 16 }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 12, opacity: 0.55 }}>
                                <Tv size={18} /><span style={{ fontSize: 13 }}>The code looks like <span className="mono">ABCD-1234</span></span>
                            </div>
                            <Field label="TV code">
                                <input className="input mono" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="ABCD-1234" autoCapitalize="characters" style={{ letterSpacing: 1 }} />
                            </Field>
                            <Field label="Name this TV (optional)">
                                <input className="input" value={deviceName} onChange={(e) => setDeviceName(e.target.value)} placeholder="Living room" />
                            </Field>
                            {error && <p style={{ color: 'var(--dx-danger)', fontSize: 13, margin: 0 }}>{error}</p>}
                            <button disabled={busy || !grace.allowed} className="btn btn-primary btn-block" style={{ padding: '11px 16px', fontSize: 14 }}>{busy ? 'Adding…' : 'Add this TV'}</button>
                        </form>
                    </>
                )}
            </div>
        </div>
    )
}
