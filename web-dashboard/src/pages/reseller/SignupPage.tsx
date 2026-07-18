import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { createUserWithEmailAndPassword, sendEmailVerification } from 'firebase/auth'
import { doc, serverTimestamp, setDoc } from 'firebase/firestore'
import { auth, db } from '../../firebase'

export default function SignupPage() {
    const nav = useNavigate()
    const [email, setEmail] = useState('')
    const [name, setName] = useState('')
    const [password, setPassword] = useState('')
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')

    async function submit(e: React.FormEvent) {
        e.preventDefault()
        setError('')
        if (password.length < 6) { setError('Password must be at least 6 characters.'); return }
        setBusy(true)
        try {
            const cred = await createUserWithEmailAndPassword(auth, email.trim(), password)
            // Reseller starts at zero credits, active. Rules enforce credits==0 on create.
            await setDoc(doc(db, 'resellers', cred.user.uid), {
                email: email.trim(),
                displayName: name.trim() || email.trim(),
                credits: 0,
                status: 'active',
                clientCount: 0,
                createdAt: serverTimestamp(),
            })
            try { await sendEmailVerification(cred.user) } catch { /* non-fatal */ }
            nav('/reseller')
        } catch (err: unknown) {
            setError(errText(err))
        } finally {
            setBusy(false)
        }
    }

    return (
        <AuthShell title="Create a reseller account" subtitle="Sell and manage your own clients.">
            <form onSubmit={submit} className="space-y-4">
                <Field label="Business / display name">
                    <input className={inputCls} value={name} onChange={(e) => setName(e.target.value)} placeholder="Acme TV" />
                </Field>
                <Field label="Email">
                    <input className={inputCls} type="email" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" />
                </Field>
                <Field label="Password">
                    <input className={inputCls} type="password" required value={password} onChange={(e) => setPassword(e.target.value)} placeholder="At least 6 characters" />
                </Field>
                {error && <p className="text-sm text-red-400">{error}</p>}
                <button disabled={busy} className={btnCls}>{busy ? 'Creating…' : 'Create account'}</button>
            </form>
            <p className="mt-6 text-center text-sm text-neutral-400">
                Already have an account?{' '}
                <Link to="/reseller/login" className="text-gold-400 hover:underline">Sign in</Link>
            </p>
        </AuthShell>
    )
}

// ── shared bits (also used by LoginPage) ───────────────────────────────
export const inputCls =
    'w-full rounded-lg border border-white/10 bg-white/5 px-3.5 py-2.5 text-sm text-white placeholder:text-neutral-500 outline-none focus:border-gold-500/60'
export const btnCls =
    'w-full rounded-lg bg-gold-500 px-4 py-2.5 text-sm font-semibold text-black transition hover:bg-gold-400 disabled:opacity-50'

export function Field({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <label className="block">
            <span className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-neutral-400">{label}</span>
            {children}
        </label>
    )
}

export function AuthShell({ title, subtitle, children }: { title: string; subtitle: string; children: React.ReactNode }) {
    return (
        <div className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-5 py-10">
            <div className="mb-8 text-center">
                <div className="mx-auto mb-3 flex h-11 w-11 items-center justify-center rounded-xl bg-gold-500 font-black text-black">DX</div>
                <h1 className="text-xl font-bold">{title}</h1>
                <p className="mt-1 text-sm text-neutral-400">{subtitle}</p>
            </div>
            <div className="rounded-2xl border border-white/10 bg-neutral-900/60 p-6">{children}</div>
        </div>
    )
}

export function errText(err: unknown): string {
    const code = (err as { code?: string })?.code || ''
    if (code.includes('email-already-in-use')) return 'That email is already registered.'
    if (code.includes('invalid-email')) return 'Enter a valid email address.'
    if (code.includes('wrong-password') || code.includes('invalid-credential')) return 'Wrong email or password.'
    if (code.includes('user-not-found')) return 'No account with that email.'
    if (code.includes('too-many-requests')) return 'Too many attempts — try again later.'
    return (err as { message?: string })?.message || 'Something went wrong.'
}
