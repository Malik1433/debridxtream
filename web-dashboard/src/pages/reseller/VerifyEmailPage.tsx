import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { sendEmailVerification, signOut } from 'firebase/auth'
import { MailCheck } from 'lucide-react'
import { auth } from '../../firebase'
import { AuthShell, btnCls } from './authUi'

export default function VerifyEmailPage() {
    const nav = useNavigate()
    const [sent, setSent] = useState(false)
    const [checking, setChecking] = useState(false)
    const [error, setError] = useState('')

    // If not signed in at all, go to login. If already verified, go straight in.
    useEffect(() => {
        const u = auth.currentUser
        if (!u) { nav('/reseller/login', { replace: true }); return }
        if (u.emailVerified) nav('/reseller', { replace: true })
    }, [nav])

    // Auto-detect verification: reload the user every few seconds.
    useEffect(() => {
        const id = setInterval(async () => {
            const u = auth.currentUser
            if (!u) return
            try {
                await u.reload()
                if (u.emailVerified) { clearInterval(id); nav('/reseller', { replace: true }) }
            } catch { /* ignore transient */ }
        }, 4000)
        return () => clearInterval(id)
    }, [nav])

    async function resend() {
        setError('')
        const u = auth.currentUser
        if (!u) return
        try { await sendEmailVerification(u); setSent(true) }
        catch { setError('Could not resend right now — try again in a minute.') }
    }

    async function checkNow() {
        setChecking(true)
        setError('')
        const u = auth.currentUser
        try {
            await u?.reload()
            if (u?.emailVerified) nav('/reseller', { replace: true })
            else setError('Not verified yet. Click the link in your email, then try again.')
        } catch {
            setError('Could not check right now — try again.')
        } finally {
            setChecking(false)
        }
    }

    return (
        <AuthShell title="Verify your email" subtitle="One quick step before you can start selling.">
            <div className="rounded-2xl border border-white/10 bg-neutral-900/60 p-6 text-center">
                <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-gold-500/15 text-gold-300">
                    <MailCheck size={22} />
                </div>
                <p className="text-sm text-neutral-300">
                    We sent a verification link to <span className="font-semibold text-white">{auth.currentUser?.email}</span>.
                    Click it, and this page will continue automatically.
                </p>
                {sent && <p className="mt-3 text-sm text-emerald-400">Verification email sent again.</p>}
                {error && <p className="mt-3 text-sm text-red-400">{error}</p>}
                <div className="mt-6 space-y-3">
                    <button onClick={checkNow} disabled={checking} className={btnCls}>
                        {checking ? 'Checking…' : "I've verified — continue"}
                    </button>
                    <button onClick={resend} className="w-full rounded-lg border border-white/10 px-4 py-2.5 text-sm text-neutral-200 hover:bg-white/5">
                        Resend email
                    </button>
                </div>
            </div>
            <button onClick={() => signOut(auth).then(() => nav('/reseller/login'))} className="mx-auto mt-6 block text-sm text-neutral-400 hover:underline">
                Sign out
            </button>
        </AuthShell>
    )
}
