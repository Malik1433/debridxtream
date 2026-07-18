import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { createUserWithEmailAndPassword, sendEmailVerification, updateProfile } from 'firebase/auth'
import { doc, serverTimestamp, setDoc } from 'firebase/firestore'
import { auth, db } from '../../firebase'
import { AuthShell, Field, btnCls, errText, inputCls, COUNTRIES } from './authUi'

export default function SignupPage() {
    const nav = useNavigate()
    const [form, setForm] = useState({
        name: '', email: '', password: '', phone: '', country: '', contact: '',
    })
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')
    const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
        setForm((f) => ({ ...f, [k]: e.target.value }))

    async function submit(e: React.FormEvent) {
        e.preventDefault()
        setError('')
        if (!form.name.trim()) { setError('Enter your business / display name.'); return }
        if (form.password.length < 6) { setError('Password must be at least 6 characters.'); return }
        setBusy(true)
        try {
            const cred = await createUserWithEmailAndPassword(auth, form.email.trim(), form.password)
            try { await updateProfile(cred.user, { displayName: form.name.trim() }) } catch { /* non-fatal */ }
            // Reseller starts at zero credits, active. Rules enforce credits==0 on create.
            await setDoc(doc(db, 'resellers', cred.user.uid), {
                email: form.email.trim(),
                displayName: form.name.trim(),
                phone: form.phone.trim(),
                country: form.country,
                contact: form.contact.trim(),
                credits: 0,
                status: 'active',
                clientCount: 0,
                createdAt: serverTimestamp(),
            })
            try { await sendEmailVerification(cred.user) } catch { /* non-fatal */ }
            nav('/reseller/verify')
        } catch (err: unknown) {
            setError(errText(err))
        } finally {
            setBusy(false)
        }
    }

    return (
        <AuthShell
            title="Become a reseller"
            subtitle="Create your account, buy credits, and manage your own clients."
        >
            <form onSubmit={submit} className="space-y-4">
                <Field label="Business / display name">
                    <input className={inputCls} value={form.name} onChange={set('name')} placeholder="Acme TV" />
                </Field>
                <Field label="Email">
                    <input className={inputCls} type="email" required value={form.email} onChange={set('email')} placeholder="you@example.com" />
                </Field>
                <div className="grid grid-cols-2 gap-3">
                    <Field label="Phone">
                        <input className={inputCls} value={form.phone} onChange={set('phone')} placeholder="+1 555 0100" />
                    </Field>
                    <Field label="Country">
                        <select className={inputCls} value={form.country} onChange={set('country')}>
                            <option value="">Select…</option>
                            {COUNTRIES.map((c) => <option key={c} value={c}>{c}</option>)}
                        </select>
                    </Field>
                </div>
                <Field label="WhatsApp / Telegram (optional)">
                    <input className={inputCls} value={form.contact} onChange={set('contact')} placeholder="@handle or number" />
                </Field>
                <Field label="Password">
                    <input className={inputCls} type="password" required value={form.password} onChange={set('password')} placeholder="At least 6 characters" />
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
