import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { sendEmailVerification, signOut } from 'firebase/auth'
import { Mail } from 'lucide-react'
import { auth } from '../../firebase'
import { CardShell } from './authUi'

export default function VerifyEmailPage() {
    const nav = useNavigate()
    const [sent, setSent] = useState(false)
    const [checking, setChecking] = useState(false)
    const [error, setError] = useState('')

    useEffect(() => {
        const u = auth.currentUser
        if (!u) { nav('/reseller/login', { replace: true }); return }
        if (u.emailVerified) nav('/reseller', { replace: true })
    }, [nav])

    useEffect(() => {
        const id = setInterval(async () => {
            const u = auth.currentUser
            if (!u) return
            try { await u.reload(); if (u.emailVerified) { clearInterval(id); nav('/reseller', { replace: true }) } } catch { /* ignore */ }
        }, 4000)
        return () => clearInterval(id)
    }, [nav])

    async function resend() {
        setError('')
        const u = auth.currentUser
        if (!u) return
        try { await sendEmailVerification(u); setSent(true) } catch { setError('Could not resend right now — try again in a minute.') }
    }
    async function checkNow() {
        setChecking(true); setError('')
        const u = auth.currentUser
        try { await u?.reload(); if (u?.emailVerified) nav('/reseller', { replace: true }); else setError('Not verified yet. Click the link in your email, then try again.') }
        catch { setError('Could not check right now — try again.') } finally { setChecking(false) }
    }

    return (
        <CardShell title="Verify your email" subtitle="One quick step before you can start selling.">
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
                    <button onClick={checkNow} disabled={checking} className="btn btn-primary btn-block" style={{ justifyContent: 'center', padding: '11px 16px', fontSize: 14 }}>{checking ? 'Checking…' : "I've verified — continue"}</button>
                    <button onClick={resend} className="btn btn-secondary btn-block" style={{ justifyContent: 'center' }}>Resend email</button>
                </div>
            </div>
            <button onClick={() => signOut(auth).then(() => nav('/reseller/login'))} className="btn btn-ghost" style={{ display: 'block', margin: '18px auto 0', opacity: 0.5, fontSize: 13 }}>Sign out</button>
        </CardShell>
    )
}
