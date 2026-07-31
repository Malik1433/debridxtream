import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { sendEmailVerification, signOut } from 'firebase/auth'
import { Mail } from 'lucide-react'
import { auth } from '../../firebase'
import { CardShell, withSearch } from './accountUi'

export default function AccountVerifyEmailPage() {
    const nav = useNavigate()
    const { search } = useLocation()
    const [sent, setSent] = useState(false)
    const [error, setError] = useState('')

    useEffect(() => {
        const u = auth.currentUser
        if (!u) { nav(withSearch('/account/login', search), { replace: true }); return }
        if (u.emailVerified) nav(withSearch('/account', search), { replace: true })
    }, [nav, search])

    useEffect(() => {
        const id = setInterval(async () => {
            const u = auth.currentUser
            if (!u) return
            try { await u.reload(); if (u.emailVerified) { clearInterval(id); nav(withSearch('/account', search), { replace: true }) } } catch { /* ignore */ }
        }, 4000)
        return () => clearInterval(id)
    }, [nav, search])

    async function resend() {
        setError('')
        const u = auth.currentUser
        if (!u) return
        try { await sendEmailVerification(u); setSent(true) } catch { setError('Could not resend right now — try again in a minute.') }
    }

    return (
        <CardShell title="Verify your email" subtitle="So you can get back in if you forget your password.">
            <div className="card" style={{ padding: 28, textAlign: 'center' }}>
                <div style={{ width: 60, height: 60, background: 'var(--color-accent-100)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 16px' }}>
                    <Mail size={24} color="#B45309" />
                </div>
                <p style={{ fontSize: 14, opacity: 0.8, margin: 0 }}>We sent a verification link to</p>
                <div className="mono" style={{ display: 'inline-block', background: 'var(--color-neutral-100)', padding: '7px 12px', margin: '10px 0', fontSize: 13 }}>{auth.currentUser?.email}</div>
                <p style={{ fontSize: 13, opacity: 0.6, margin: 0 }}>Click it and this page continues automatically.</p>
                {sent && <p style={{ color: 'var(--dx-success)', fontSize: 13 }}>Verification email sent again.</p>}
                {error && <p style={{ color: 'var(--dx-danger)', fontSize: 13 }}>{error}</p>}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 20 }}>
                    {/* The reseller portal blocks here until verified. A customer must not be: they may be
                        standing in front of a TV they just paid for, with their email on another device.
                        §7 D5 gives them a bounded window instead of a wall. */}
                    <button onClick={() => nav(withSearch('/account', search), { replace: true })} className="btn btn-primary btn-block" style={{ justifyContent: 'center', padding: '11px 16px', fontSize: 14 }}>Continue — I'll verify later</button>
                    <button onClick={resend} className="btn btn-secondary btn-block" style={{ justifyContent: 'center' }}>Resend email</button>
                </div>
            </div>
            <button onClick={() => signOut(auth).then(() => nav(withSearch('/account/login', search)))} className="btn btn-ghost" style={{ display: 'block', margin: '18px auto 0', opacity: 0.5, fontSize: 13 }}>Sign out</button>
        </CardShell>
    )
}
