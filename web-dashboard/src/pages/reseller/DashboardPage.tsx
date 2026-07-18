import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { EmailAuthProvider, reauthenticateWithCredential, signOut, updatePassword } from 'firebase/auth'
import { collection, doc, onSnapshot, query, setDoc, where } from 'firebase/firestore'
import { httpsCallable } from 'firebase/functions'
import {
    CreditCard, LayoutDashboard, LogOut, Plus, ReceiptText, RefreshCw, Ticket, Users, UserRound, Wallet,
} from 'lucide-react'
import { auth, db, functions } from '../../firebase'
import { useAuth } from '../../reseller/useAuth'
import { COUNTRIES, errText } from './authUi'

interface Plan { id: string; name: string; tier: string; months: number; cost: number }
interface Pkg { id: string; name: string; credits: number; priceUsd: number; bonus?: number }
interface Client { id: string; activationCode?: string; tier?: string; status?: string; expiresAt?: number }
interface Ledger { id: string; delta: number; reason: string; at: number; balanceAfter?: number }

type View = 'overview' | 'clients' | 'buy' | 'billing' | 'profile'

export default function DashboardPage() {
    const nav = useNavigate()
    const { user, reseller, loading } = useAuth()
    const [view, setView] = useState<View>('overview')
    const [plans, setPlans] = useState<Plan[]>([])
    const [packages, setPackages] = useState<Pkg[]>([])
    const [clients, setClients] = useState<Client[]>([])
    const [ledger, setLedger] = useState<Ledger[]>([])
    const [toast, setToast] = useState('')

    useEffect(() => {
        if (loading) return
        if (!user) { nav('/reseller/login', { replace: true }); return }
        if (!user.emailVerified) nav('/reseller/verify', { replace: true })
    }, [loading, user, nav])

    useEffect(() => onSnapshot(collection(db, 'plans'), (s) => {
        const l = s.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<Plan, 'id'>) })); l.sort((a, b) => a.cost - b.cost); setPlans(l)
    }), [])
    useEffect(() => onSnapshot(collection(db, 'credit_packages'), (s) => {
        const l = s.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<Pkg, 'id'>) })); l.sort((a, b) => a.priceUsd - b.priceUsd); setPackages(l)
    }, () => {/* none yet */}), [])
    useEffect(() => {
        if (!user) return
        return onSnapshot(query(collection(db, 'licenses'), where('resellerId', '==', user.uid)), (s) => {
            const l = s.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<Client, 'id'>) })); l.sort((a, b) => (b.expiresAt || 0) - (a.expiresAt || 0)); setClients(l)
        }, () => {})
    }, [user])
    useEffect(() => {
        if (!user) return
        return onSnapshot(query(collection(db, 'credit_ledger'), where('resellerId', '==', user.uid)), (s) => {
            const l = s.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<Ledger, 'id'>) })); l.sort((a, b) => (b.at || 0) - (a.at || 0)); setLedger(l)
        }, () => {})
    }, [user])

    const showToast = (t: string) => { setToast(t); setTimeout(() => setToast(''), 2600) }

    const stats = useMemo(() => {
        const now = Date.now()
        const active = clients.filter((c) => c.status === 'active' && (!c.expiresAt || c.expiresAt > now)).length
        const soon = clients.filter((c) => c.status === 'active' && c.expiresAt && c.expiresAt > now && c.expiresAt < now + 7 * 864e5).length
        return { total: clients.length, active, soon }
    }, [clients])

    if (loading || !user) return <div className="grid min-h-screen place-items-center text-neutral-400">Loading…</div>

    const navItems: { id: View; label: string; icon: React.ReactNode }[] = [
        { id: 'overview', label: 'Overview', icon: <LayoutDashboard size={17} /> },
        { id: 'clients', label: 'Clients', icon: <Users size={17} /> },
        { id: 'buy', label: 'Buy Credits', icon: <Wallet size={17} /> },
        { id: 'billing', label: 'Billing', icon: <ReceiptText size={17} /> },
        { id: 'profile', label: 'Profile', icon: <UserRound size={17} /> },
    ]

    return (
        <div className="flex min-h-screen">
            {/* sidebar */}
            <aside className="hidden w-60 shrink-0 flex-col border-r border-white/10 bg-neutral-900/50 p-4 sm:flex">
                <div className="mb-8 flex items-center gap-2.5 px-2">
                    <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-gold-500 font-black text-black">DX</div>
                    <span className="text-sm font-bold">Reseller</span>
                </div>
                <nav className="space-y-1">
                    {navItems.map((n) => (
                        <button key={n.id} onClick={() => setView(n.id)}
                            className={`flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition ${view === n.id ? 'bg-gold-500/15 text-gold-300' : 'text-neutral-400 hover:bg-white/5 hover:text-white'}`}>
                            {n.icon} {n.label}
                        </button>
                    ))}
                </nav>
                <button onClick={() => signOut(auth)} className="mt-auto flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm text-neutral-400 hover:bg-white/5 hover:text-white">
                    <LogOut size={17} /> Sign out
                </button>
            </aside>

            {/* main */}
            <main className="flex-1">
                {/* topbar */}
                <div className="flex items-center justify-between border-b border-white/10 px-5 py-3.5">
                    <div className="flex items-center gap-2 sm:hidden">
                        <select value={view} onChange={(e) => setView(e.target.value as View)} className="rounded-lg border border-white/10 bg-white/5 px-2 py-1.5 text-sm">
                            {navItems.map((n) => <option key={n.id} value={n.id}>{n.label}</option>)}
                        </select>
                    </div>
                    <h1 className="hidden text-lg font-semibold capitalize sm:block">{view === 'buy' ? 'Buy Credits' : view}</h1>
                    <div className="flex items-center gap-3">
                        <div className="rounded-xl border border-gold-500/30 bg-gold-500/10 px-3.5 py-1.5">
                            <span className="text-[10px] uppercase tracking-wide text-gold-300/80">Credits </span>
                            <span className="font-bold text-gold-300">{reseller?.credits ?? 0}</span>
                        </div>
                        <button onClick={() => setView('buy')} className="rounded-lg bg-gold-500 px-3 py-1.5 text-sm font-semibold text-black hover:bg-gold-400">Buy</button>
                    </div>
                </div>

                <div className="p-5">
                    {view === 'overview' && <Overview stats={stats} ledger={ledger} name={reseller?.displayName || user.email || ''} onBuy={() => setView('buy')} />}
                    {view === 'clients' && <Clients plans={plans} clients={clients} showToast={showToast} />}
                    {view === 'buy' && <BuyCredits packages={packages} showToast={showToast} />}
                    {view === 'billing' && <Billing ledger={ledger} />}
                    {view === 'profile' && <Profile uid={user.uid} reseller={reseller} showToast={showToast} />}
                </div>
            </main>

            {toast && <div className="fixed bottom-5 right-5 rounded-lg border border-gold-500/40 bg-neutral-900 px-4 py-2.5 text-sm shadow-lg">{toast}</div>}
        </div>
    )
}

// ── Overview ────────────────────────────────────────────────────────────
function Overview({ stats, ledger, name, onBuy }: { stats: { total: number; active: number; soon: number }; ledger: Ledger[]; name: string; onBuy: () => void }) {
    return (
        <div className="space-y-6">
            <p className="text-sm text-neutral-400">Welcome back, <span className="text-white">{name}</span>.</p>
            <div className="grid gap-4 sm:grid-cols-3">
                <StatCard label="Active clients" value={stats.active} icon={<Users size={18} />} />
                <StatCard label="Expiring in 7 days" value={stats.soon} icon={<RefreshCw size={18} />} accent={stats.soon > 0} />
                <StatCard label="Total clients" value={stats.total} icon={<Ticket size={18} />} />
            </div>
            <div className="rounded-2xl border border-white/10 bg-neutral-900/60">
                <div className="flex items-center justify-between border-b border-white/10 px-5 py-3">
                    <span className="text-sm font-semibold">Recent activity</span>
                    <button onClick={onBuy} className="text-xs text-gold-400 hover:underline">Buy credits</button>
                </div>
                {ledger.length === 0 ? (
                    <p className="px-5 py-8 text-center text-sm text-neutral-500">No activity yet.</p>
                ) : (
                    <ul className="divide-y divide-white/5">
                        {ledger.slice(0, 8).map((l) => (
                            <li key={l.id} className="flex items-center justify-between px-5 py-3 text-sm">
                                <span className="capitalize text-neutral-300">{l.reason}</span>
                                <span className={l.delta >= 0 ? 'text-emerald-400' : 'text-neutral-400'}>{l.delta >= 0 ? '+' : ''}{l.delta} cr</span>
                            </li>
                        ))}
                    </ul>
                )}
            </div>
        </div>
    )
}
function StatCard({ label, value, icon, accent }: { label: string; value: number; icon: React.ReactNode; accent?: boolean }) {
    return (
        <div className={`rounded-2xl border p-5 ${accent ? 'border-amber-500/30 bg-amber-500/5' : 'border-white/10 bg-neutral-900/60'}`}>
            <div className="mb-2 flex items-center gap-2 text-neutral-400">{icon}<span className="text-xs uppercase tracking-wide">{label}</span></div>
            <p className="text-2xl font-bold">{value}</p>
        </div>
    )
}

// ── Clients ─────────────────────────────────────────────────────────────
function Clients({ plans, clients, showToast }: { plans: Plan[]; clients: Client[]; showToast: (t: string) => void }) {
    const [code, setCode] = useState('')
    const [planId, setPlanId] = useState('')
    const [busy, setBusy] = useState(false)
    // Renew confirmation: which client + which plan is being renewed.
    const [renewFor, setRenewFor] = useState<Client | null>(null)
    const [renewPlanId, setRenewPlanId] = useState('')
    useEffect(() => { setPlanId((cur) => cur || plans[0]?.id || '') }, [plans])

    async function call(name: 'activateClient' | 'renewClient', payload: object): Promise<boolean> {
        setBusy(true)
        try {
            const res = await httpsCallable(functions, name)(payload)
            const d = res.data as { creditsLeft?: number }
            showToast(`Done. Credits left: ${d?.creditsLeft ?? '—'}.`)
            if (name === 'activateClient') setCode('')
            return true
        } catch (e) { showToast(errText(e)); return false } finally { setBusy(false) }
    }

    function openRenew(c: Client) {
        setRenewPlanId(plans[0]?.id || '')
        setRenewFor(c)
    }
    async function confirmRenew() {
        if (!renewFor || !renewPlanId) return
        const ok = await call('renewClient', { installId: renewFor.id, planId: renewPlanId })
        if (ok) setRenewFor(null)
    }
    const renewPlan = plans.find((p) => p.id === renewPlanId)

    return (
        <div className="space-y-5">
            <div className="rounded-2xl border border-white/10 bg-neutral-900/60 p-5">
                <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold"><Ticket size={16} /> Add a client</h2>
                <form onSubmit={(e) => { e.preventDefault(); if (code.trim() && planId) call('activateClient', { activationCode: code.trim(), planId }) }} className="flex flex-col gap-3 sm:flex-row sm:items-end">
                    <label className="flex-1">
                        <span className="mb-1.5 block text-xs uppercase tracking-wide text-neutral-400">Activation code (from the TV)</span>
                        <input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="XXXX-XXXX" className="w-full rounded-lg border border-white/10 bg-white/5 px-3.5 py-2.5 font-mono text-sm outline-none focus:border-gold-500/60" />
                    </label>
                    <label className="sm:w-56">
                        <span className="mb-1.5 block text-xs uppercase tracking-wide text-neutral-400">Plan</span>
                        <select value={planId} onChange={(e) => setPlanId(e.target.value)} className="w-full rounded-lg border border-white/10 bg-white/5 px-3.5 py-2.5 text-sm outline-none focus:border-gold-500/60">
                            {plans.length === 0 && <option value="">No plans yet</option>}
                            {plans.map((p) => <option key={p.id} value={p.id}>{p.name} · {p.cost} cr</option>)}
                        </select>
                    </label>
                    <button disabled={busy || !code.trim() || !planId} className="inline-flex items-center justify-center gap-1.5 rounded-lg bg-gold-500 px-4 py-2.5 text-sm font-semibold text-black hover:bg-gold-400 disabled:opacity-50"><Plus size={16} /> Activate</button>
                </form>
            </div>
            <div className="rounded-2xl border border-white/10 bg-neutral-900/60">
                <div className="border-b border-white/10 px-5 py-3 text-sm font-semibold">Your clients ({clients.length})</div>
                {clients.length === 0 ? <p className="px-5 py-8 text-center text-sm text-neutral-500">No clients yet. Enter a device's activation code above.</p> : (
                    <div className="overflow-x-auto">
                        <table className="w-full text-sm">
                            <thead className="text-left text-xs uppercase tracking-wide text-neutral-500"><tr className="border-b border-white/5">
                                <th className="px-5 py-2.5 font-medium">Code</th><th className="px-3 py-2.5 font-medium">Tier</th><th className="px-3 py-2.5 font-medium">Status</th><th className="px-3 py-2.5 font-medium">Expires</th><th className="px-5 py-2.5" />
                            </tr></thead>
                            <tbody>
                                {clients.map((c) => (
                                    <tr key={c.id} className="border-b border-white/5 last:border-0">
                                        <td className="px-5 py-3 font-mono">{c.activationCode || c.id.slice(0, 8)}</td>
                                        <td className="px-3 py-3"><span className={c.tier === 'premium' ? 'text-gold-300' : 'text-neutral-300'}>{c.tier || '—'}</span></td>
                                        <td className="px-3 py-3"><StatusPill status={c.status} expiresAt={c.expiresAt} /></td>
                                        <td className="px-3 py-3 text-neutral-300">{fmtDate(c.expiresAt)}</td>
                                        <td className="px-5 py-3 text-right"><button onClick={() => openRenew(c)} disabled={busy || plans.length === 0} className="inline-flex items-center gap-1 rounded-md border border-white/10 px-2.5 py-1.5 text-xs hover:bg-white/5 disabled:opacity-50"><RefreshCw size={13} /> Renew</button></td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>

            {/* Renew confirmation — spending credits should never be a silent one-click. */}
            {renewFor && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onClick={() => !busy && setRenewFor(null)}>
                    <div className="w-full max-w-sm rounded-2xl border border-white/10 bg-neutral-900 p-6" onClick={(e) => e.stopPropagation()}>
                        <h3 className="text-base font-semibold">Renew client</h3>
                        <p className="mt-1 text-sm text-neutral-400">
                            Device <span className="font-mono text-neutral-200">{renewFor.activationCode || renewFor.id.slice(0, 8)}</span>
                        </p>
                        <label className="mt-4 block">
                            <span className="mb-1.5 block text-xs uppercase tracking-wide text-neutral-400">Plan</span>
                            <select value={renewPlanId} onChange={(e) => setRenewPlanId(e.target.value)} className="w-full rounded-lg border border-white/10 bg-white/5 px-3.5 py-2.5 text-sm outline-none focus:border-gold-500/60">
                                {plans.map((p) => <option key={p.id} value={p.id}>{p.name} · {p.cost} cr · {p.months}mo</option>)}
                            </select>
                        </label>
                        {renewPlan && (
                            <div className="mt-3 rounded-lg bg-white/5 px-3.5 py-2.5 text-sm text-neutral-300">
                                Extends by <span className="text-white">{renewPlan.months} month(s)</span> · costs <span className="font-semibold text-gold-300">{renewPlan.cost} credits</span>
                                <div className="mt-1 text-xs text-neutral-500">New expiry: {fmtDate(Math.max(Date.now(), renewFor.expiresAt || 0) + renewPlan.months * 30 * 864e5)}</div>
                            </div>
                        )}
                        <div className="mt-5 flex gap-3">
                            <button onClick={() => setRenewFor(null)} disabled={busy} className="flex-1 rounded-lg border border-white/10 px-4 py-2.5 text-sm hover:bg-white/5 disabled:opacity-50">Cancel</button>
                            <button onClick={confirmRenew} disabled={busy || !renewPlanId} className="flex-1 rounded-lg bg-gold-500 px-4 py-2.5 text-sm font-semibold text-black hover:bg-gold-400 disabled:opacity-50">{busy ? 'Renewing…' : 'Confirm renew'}</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    )
}

// ── Buy Credits ─────────────────────────────────────────────────────────
function BuyCredits({ packages, showToast }: { packages: Pkg[]; showToast: (t: string) => void }) {
    return (
        <div className="space-y-5">
            <p className="text-sm text-neutral-400">Buy credits, then use them to activate and renew your clients.</p>
            {packages.length === 0 ? (
                <div className="rounded-2xl border border-white/10 bg-neutral-900/60 p-8 text-center text-sm text-neutral-500">
                    No credit packages available yet. Please check back soon.
                </div>
            ) : (
                <div className="grid gap-4 sm:grid-cols-3">
                    {packages.map((p) => (
                        <div key={p.id} className="rounded-2xl border border-white/10 bg-neutral-900/60 p-5">
                            <p className="text-sm text-neutral-400">{p.name}</p>
                            <p className="mt-2 text-3xl font-bold text-gold-300">{p.credits}<span className="text-base font-normal text-neutral-400"> cr</span></p>
                            {p.bonus ? <p className="text-xs text-emerald-400">+{p.bonus} bonus</p> : null}
                            <p className="mt-1 text-sm text-neutral-300">${p.priceUsd.toFixed(2)}</p>
                            <button onClick={() => showToast('Checkout coming soon (PayPal).')} className="mt-4 flex w-full items-center justify-center gap-1.5 rounded-lg bg-gold-500 px-4 py-2.5 text-sm font-semibold text-black hover:bg-gold-400">
                                <CreditCard size={16} /> Buy
                            </button>
                        </div>
                    ))}
                </div>
            )}
            <p className="text-xs text-neutral-500">Payments are processed securely. Credits are added automatically once your payment is confirmed.</p>
        </div>
    )
}

// ── Billing ─────────────────────────────────────────────────────────────
function Billing({ ledger }: { ledger: Ledger[] }) {
    return (
        <div className="rounded-2xl border border-white/10 bg-neutral-900/60">
            <div className="border-b border-white/10 px-5 py-3 text-sm font-semibold">Credit history</div>
            {ledger.length === 0 ? <p className="px-5 py-8 text-center text-sm text-neutral-500">No transactions yet.</p> : (
                <table className="w-full text-sm">
                    <thead className="text-left text-xs uppercase tracking-wide text-neutral-500"><tr className="border-b border-white/5"><th className="px-5 py-2.5 font-medium">When</th><th className="px-3 py-2.5 font-medium">Type</th><th className="px-3 py-2.5 font-medium">Change</th><th className="px-5 py-2.5 font-medium">Balance</th></tr></thead>
                    <tbody>
                        {ledger.map((l) => (
                            <tr key={l.id} className="border-b border-white/5 last:border-0">
                                <td className="px-5 py-3 text-neutral-400">{fmtDateTime(l.at)}</td>
                                <td className="px-3 py-3 capitalize">{l.reason}</td>
                                <td className={`px-3 py-3 ${l.delta >= 0 ? 'text-emerald-400' : 'text-neutral-300'}`}>{l.delta >= 0 ? '+' : ''}{l.delta}</td>
                                <td className="px-5 py-3 text-neutral-300">{l.balanceAfter ?? '—'}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </div>
    )
}

// ── Profile ─────────────────────────────────────────────────────────────
function Profile({ uid, reseller, showToast }: { uid: string; reseller: ReturnType<typeof useAuth>['reseller']; showToast: (t: string) => void }) {
    const [form, setForm] = useState({ displayName: '', phone: '', country: '', contact: '' })
    const [pw, setPw] = useState({ current: '', next: '' })
    const [busy, setBusy] = useState(false)
    useEffect(() => {
        if (reseller) setForm({ displayName: reseller.displayName || '', phone: (reseller as { phone?: string }).phone || '', country: (reseller as { country?: string }).country || '', contact: (reseller as { contact?: string }).contact || '' })
    }, [reseller])

    async function saveProfile() {
        setBusy(true)
        try { await setDoc(doc(db, 'resellers', uid), form, { merge: true }); showToast('Profile saved.') }
        catch (e) { showToast(errText(e)) } finally { setBusy(false) }
    }
    async function changePassword() {
        const u = auth.currentUser
        if (!u || !u.email) return
        if (pw.next.length < 6) { showToast('New password must be at least 6 characters.'); return }
        setBusy(true)
        try {
            await reauthenticateWithCredential(u, EmailAuthProvider.credential(u.email, pw.current))
            await updatePassword(u, pw.next)
            setPw({ current: '', next: '' }); showToast('Password changed.')
        } catch (e) { showToast(errText(e)) } finally { setBusy(false) }
    }
    const inp = 'w-full rounded-lg border border-white/10 bg-white/5 px-3.5 py-2.5 text-sm outline-none focus:border-gold-500/60'
    return (
        <div className="grid max-w-3xl gap-5 md:grid-cols-2">
            <div className="rounded-2xl border border-white/10 bg-neutral-900/60 p-5">
                <h2 className="mb-4 text-sm font-semibold">Your details</h2>
                <div className="space-y-3">
                    <div><span className="mb-1 block text-xs uppercase tracking-wide text-neutral-400">Business name</span><input className={inp} value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} /></div>
                    <div><span className="mb-1 block text-xs uppercase tracking-wide text-neutral-400">Phone</span><input className={inp} value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></div>
                    <div><span className="mb-1 block text-xs uppercase tracking-wide text-neutral-400">Country</span><select className={inp} value={form.country} onChange={(e) => setForm({ ...form, country: e.target.value })}><option value="">Select…</option>{COUNTRIES.map((c) => <option key={c} value={c}>{c}</option>)}</select></div>
                    <div><span className="mb-1 block text-xs uppercase tracking-wide text-neutral-400">WhatsApp / Telegram</span><input className={inp} value={form.contact} onChange={(e) => setForm({ ...form, contact: e.target.value })} /></div>
                    <button onClick={saveProfile} disabled={busy} className="rounded-lg bg-gold-500 px-4 py-2 text-sm font-semibold text-black hover:bg-gold-400 disabled:opacity-50">Save profile</button>
                </div>
            </div>
            <div className="rounded-2xl border border-white/10 bg-neutral-900/60 p-5">
                <h2 className="mb-4 text-sm font-semibold">Change password</h2>
                <div className="space-y-3">
                    <div><span className="mb-1 block text-xs uppercase tracking-wide text-neutral-400">Current password</span><input type="password" className={inp} value={pw.current} onChange={(e) => setPw({ ...pw, current: e.target.value })} /></div>
                    <div><span className="mb-1 block text-xs uppercase tracking-wide text-neutral-400">New password</span><input type="password" className={inp} value={pw.next} onChange={(e) => setPw({ ...pw, next: e.target.value })} /></div>
                    <button onClick={changePassword} disabled={busy} className="rounded-lg border border-white/10 px-4 py-2 text-sm hover:bg-white/5 disabled:opacity-50">Update password</button>
                </div>
            </div>
        </div>
    )
}

// ── shared bits ─────────────────────────────────────────────────────────
function StatusPill({ status, expiresAt }: { status?: string; expiresAt?: number }) {
    const expired = status === 'active' && expiresAt && expiresAt > 0 && expiresAt < Date.now()
    const label = expired ? 'expired' : status || 'unknown'
    const cls = expired ? 'bg-red-500/15 text-red-300' : status === 'active' ? 'bg-emerald-500/15 text-emerald-300' : status === 'pending' ? 'bg-amber-500/15 text-amber-300' : 'bg-white/10 text-neutral-300'
    return <span className={`rounded-full px-2 py-0.5 text-xs ${cls}`}>{label}</span>
}
function fmtDate(ms?: number): string { return !ms || ms <= 0 ? '—' : new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) }
function fmtDateTime(ms?: number): string { return !ms || ms <= 0 ? '—' : new Date(ms).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }
