import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { EmailAuthProvider, reauthenticateWithCredential, signOut, updatePassword } from 'firebase/auth'
import { collection, doc, onSnapshot, query, setDoc, where } from 'firebase/firestore'
import { httpsCallable } from 'firebase/functions'
import { CreditCard, LayoutDashboard, LogOut, ReceiptText, RefreshCw, ShieldCheck, Ticket, UserRound, Users, Wallet } from 'lucide-react'
import { auth, db, functions } from '../../firebase'
import { useAuth } from '../../reseller/useAuth'
import { COUNTRIES, DxLogo, errText } from './authUi'

interface Plan { id: string; name: string; tier: string; months: number; cost: number }
interface Pkg { id: string; name: string; credits: number; priceUsd: number; bonus?: number }
interface Client { id: string; activationCode?: string; tier?: string; status?: string; expiresAt?: number; planId?: string }
interface Ledger { id: string; delta: number; reason: string; at: number; balanceAfter?: number }
type View = 'overview' | 'clients' | 'buy' | 'billing' | 'profile'

const NAV: { id: View; label: string; icon: React.ReactNode }[] = [
    { id: 'overview', label: 'Overview', icon: <LayoutDashboard size={16} /> },
    { id: 'clients', label: 'Clients', icon: <Users size={16} /> },
    { id: 'buy', label: 'Buy Credits', icon: <Wallet size={16} /> },
    { id: 'billing', label: 'Billing', icon: <ReceiptText size={16} /> },
    { id: 'profile', label: 'Profile', icon: <UserRound size={16} /> },
]

export default function DashboardPage() {
    const nav = useNavigate()
    const { user, reseller, loading } = useAuth()
    const [view, setView] = useState<View>('overview')
    const [plans, setPlans] = useState<Plan[]>([])
    const [packages, setPackages] = useState<Pkg[]>([])
    const [clients, setClients] = useState<Client[]>([])
    const [ledger, setLedger] = useState<Ledger[]>([])
    const [toast, setToast] = useState('')
    const showToast = (t: string) => { setToast(t); setTimeout(() => setToast(''), 3000) }

    useEffect(() => {
        if (loading) return
        if (!user) { nav('/reseller/login', { replace: true }); return }
        if (!user.emailVerified) nav('/reseller/verify', { replace: true })
    }, [loading, user, nav])

    useEffect(() => onSnapshot(collection(db, 'plans'), (s) => { const l = s.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<Plan, 'id'>) })); l.sort((a, b) => a.cost - b.cost); setPlans(l) }), [])
    useEffect(() => onSnapshot(collection(db, 'credit_packages'), (s) => { const l = s.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<Pkg, 'id'>) })); l.sort((a, b) => a.priceUsd - b.priceUsd); setPackages(l) }, () => {}), [])
    useEffect(() => { if (!user) return; return onSnapshot(query(collection(db, 'licenses'), where('resellerId', '==', user.uid)), (s) => { const l = s.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<Client, 'id'>) })); l.sort((a, b) => (b.expiresAt || 0) - (a.expiresAt || 0)); setClients(l) }, () => {}) }, [user])
    useEffect(() => { if (!user) return; return onSnapshot(query(collection(db, 'credit_ledger'), where('resellerId', '==', user.uid)), (s) => { const l = s.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<Ledger, 'id'>) })); l.sort((a, b) => (b.at || 0) - (a.at || 0)); setLedger(l) }, () => {}) }, [user])

    const stats = useMemo(() => {
        const now = Date.now()
        const active = clients.filter((c) => c.status === 'active' && (!c.expiresAt || c.expiresAt > now)).length
        const soon = clients.filter((c) => c.status === 'active' && c.expiresAt && c.expiresAt > now && c.expiresAt < now + 7 * 864e5).length
        return { total: clients.length, active, soon }
    }, [clients])

    if (loading || !user) return <div className="mod" style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', opacity: 0.5 }}>Loading…</div>

    return (
        <div className="mod">
            <div className="dash">
                {/* Sidebar */}
                <aside className="dash-sidebar">
                    <div style={{ padding: '18px 20px', borderBottom: '2px solid var(--color-divider)' }}><DxLogo /></div>
                    <div style={{ padding: '16px 20px', borderBottom: '2px solid var(--color-divider)' }}>
                        <div style={{ fontSize: 10, letterSpacing: '0.1em', textTransform: 'uppercase', opacity: 0.5 }}>Credits</div>
                        <div style={{ fontFamily: 'Archivo', fontWeight: 800, fontSize: 30, color: 'var(--color-accent)', lineHeight: 1.1 }}>{reseller?.credits ?? 0}</div>
                        <div style={{ fontSize: 12, opacity: 0.45 }}>available balance</div>
                    </div>
                    <nav style={{ display: 'flex', flexDirection: 'column', flex: 1, paddingTop: 8 }}>
                        {NAV.map((n) => (
                            <button key={n.id} className={`navitem${view === n.id ? ' active' : ''}`} onClick={() => setView(n.id)}>{n.icon}{n.label}</button>
                        ))}
                    </nav>
                    <div style={{ borderTop: '2px solid var(--color-divider)', padding: 12 }}>
                        <button className="btn btn-ghost" onClick={() => signOut(auth)} style={{ width: '100%', justifyContent: 'flex-start', opacity: 0.7 }}><LogOut size={15} /> Sign out</button>
                    </div>
                </aside>

                {/* Main */}
                <main className="dash-main">
                    {/* mobile nav */}
                    <div className="dash-mobilenav" style={{ borderBottom: '2px solid var(--color-divider)', padding: '10px 16px' }}>
                        <select className="input" value={view} onChange={(e) => setView(e.target.value as View)}>{NAV.map((n) => <option key={n.id} value={n.id}>{n.label}</option>)}</select>
                    </div>
                    {/* topbar */}
                    <div style={{ height: 56, borderBottom: '2px solid var(--color-divider)', display: 'flex', alignItems: 'center', padding: '0 28px', gap: 12 }}>
                        <span style={{ fontFamily: 'Archivo', fontWeight: 800, fontSize: 14, marginRight: 'auto', textTransform: 'capitalize' }}>{view === 'buy' ? 'Buy Credits' : view}</span>
                        <span className="chip" style={{ background: 'var(--color-accent-100)', color: 'var(--color-accent-800)', fontSize: 12, padding: '4px 10px' }}>{reseller?.credits ?? 0} credits</span>
                        <button className="btn btn-primary" style={{ fontSize: 12, padding: '7px 14px' }} onClick={() => setView('buy')}>Buy Credits</button>
                        <div style={{ width: 30, height: 30, background: '#201e1d', color: '#ec3013', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: 'Archivo', fontWeight: 800, fontSize: 12 }}>{(reseller?.displayName || user.email || 'R').slice(0, 1).toUpperCase()}</div>
                    </div>

                    <div style={{ padding: '28px 32px' }}>
                        {view === 'overview' && <Overview stats={stats} ledger={ledger} name={reseller?.displayName || user.email || ''} onBuy={() => setView('buy')} />}
                        {view === 'clients' && <Clients plans={plans} clients={clients} showToast={showToast} />}
                        {view === 'buy' && <BuyCredits packages={packages} showToast={showToast} />}
                        {view === 'billing' && <Billing ledger={ledger} />}
                        {view === 'profile' && <Profile uid={user.uid} reseller={reseller} showToast={showToast} />}
                    </div>
                </main>
            </div>
            {toast && <div className="mod-toast">{toast}</div>}
        </div>
    )
}

// ── Overview ──
function Overview({ stats, ledger, name, onBuy }: { stats: { total: number; active: number; soon: number }; ledger: Ledger[]; name: string; onBuy: () => void }) {
    return (
        <div>
            <p style={{ fontSize: 14, opacity: 0.6, marginTop: 0 }}>Welcome back, <strong style={{ opacity: 1 }}>{name}</strong>.</p>
            <div className="grid4" style={{ marginBottom: 24 }}>
                <Kpi label="Active clients" value={stats.active} />
                <Kpi label="Expiring in 7 days" value={stats.soon} amber={stats.soon > 0} />
                <Kpi label="Total clients" value={stats.total} />
                <Kpi label="Live now" value={stats.active} />
            </div>
            <div className="card">
                <div style={{ display: 'flex', alignItems: 'center', padding: '14px 18px', borderBottom: '2px solid var(--color-divider)' }}>
                    <span style={{ fontFamily: 'Archivo', fontWeight: 800, fontSize: 13, marginRight: 'auto' }}>Recent activity</span>
                    <button className="btn btn-ghost" style={{ fontSize: 12, padding: 0, color: 'var(--color-accent-700)' }} onClick={onBuy}>Buy credits</button>
                </div>
                {ledger.length === 0 ? <p style={{ padding: '28px', textAlign: 'center', fontSize: 13, opacity: 0.45, margin: 0 }}>No activity yet.</p> : (
                    <div>
                        {ledger.slice(0, 8).map((l) => (
                            <div key={l.id} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '11px 18px', borderBottom: '1px solid var(--color-divider)' }}>
                                <div style={{ width: 30, height: 30, background: 'var(--color-neutral-100)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><RefreshCw size={13} /></div>
                                <div style={{ marginRight: 'auto' }}>
                                    <div style={{ fontSize: 13, fontWeight: 600, textTransform: 'capitalize' }}>{l.reason}</div>
                                    <div style={{ fontSize: 11, opacity: 0.4 }}>{fmtDateTime(l.at)}</div>
                                </div>
                                <span style={{ fontFamily: 'Archivo', fontWeight: 800, fontSize: 14, color: l.delta >= 0 ? 'var(--dx-success)' : 'inherit' }}>{l.delta >= 0 ? '+' : ''}{l.delta}</span>
                            </div>
                        ))}
                    </div>
                )}
            </div>
        </div>
    )
}
function Kpi({ label, value, amber }: { label: string; value: number; amber?: boolean }) {
    return (
        <div className="cell" style={amber ? { background: '#fef3c7' } : undefined}>
            <div style={{ fontSize: 10, textTransform: 'uppercase', letterSpacing: '0.06em', opacity: 0.45 }}>{label}</div>
            <div style={{ fontFamily: 'Archivo', fontWeight: 800, fontSize: 34, color: amber ? '#92400e' : undefined, marginTop: 4 }}>{value}</div>
        </div>
    )
}

// ── Clients ──
function Clients({ plans, clients, showToast }: { plans: Plan[]; clients: Client[]; showToast: (t: string) => void }) {
    const [code, setCode] = useState('')
    const [planId, setPlanId] = useState('')
    const [busy, setBusy] = useState(false)
    const [renewFor, setRenewFor] = useState<Client | null>(null)
    const [renewPlanId, setRenewPlanId] = useState('')
    const [search, setSearch] = useState('')
    useEffect(() => { setPlanId((c) => c || plans[0]?.id || '') }, [plans])

    async function call(name: 'activateClient' | 'renewClient', payload: object): Promise<boolean> {
        setBusy(true)
        try { const res = await httpsCallable(functions, name)(payload); const d = res.data as { creditsLeft?: number }; showToast(`Done. Credits left: ${d?.creditsLeft ?? '—'}.`); if (name === 'activateClient') setCode(''); return true }
        catch (e) { showToast(errText(e)); return false } finally { setBusy(false) }
    }
    const shown = clients.filter((c) => !search || (c.activationCode || c.id).toLowerCase().includes(search.toLowerCase()))
    const renewPlan = plans.find((p) => p.id === renewPlanId)

    return (
        <div>
            <div className="card" style={{ padding: 22, marginBottom: 20 }}>
                <div className="kicker" style={{ marginBottom: 12 }}>Add a client</div>
                <form onSubmit={(e) => { e.preventDefault(); if (code.trim() && planId) call('activateClient', { activationCode: code.trim(), planId }) }} className="add-client-grid">
                    <div className="field"><label>Activation code (from the TV)</label><input className="input mono" style={{ textTransform: 'uppercase', letterSpacing: '0.08em' }} maxLength={9} value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="XXXX-XXXX" /></div>
                    <div className="field"><label>Plan</label><select className="input" value={planId} onChange={(e) => setPlanId(e.target.value)}>{plans.length === 0 && <option value="">No plans yet</option>}{plans.map((p) => <option key={p.id} value={p.id}>{p.name} · {p.cost} cr</option>)}</select></div>
                    <button className="btn btn-primary" disabled={busy || !code.trim() || !planId} style={{ padding: '9px 18px' }}><Ticket size={15} /> Activate</button>
                </form>
            </div>

            <div className="card">
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '14px 18px', borderBottom: '2px solid var(--color-divider)' }}>
                    <span style={{ fontFamily: 'Archivo', fontWeight: 800, fontSize: 13, marginRight: 'auto' }}>Your clients ({clients.length})</span>
                    <input className="input" style={{ width: 220 }} value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search code…" />
                </div>
                {shown.length === 0 ? <p style={{ padding: 28, textAlign: 'center', fontSize: 13, opacity: 0.45, margin: 0 }}>No clients yet. Enter a device's activation code above.</p> : (
                    <div style={{ overflowX: 'auto' }}>
                        <table className="table">
                            <thead><tr><th>Code</th><th>Tier</th><th>Status</th><th>Expires</th><th></th></tr></thead>
                            <tbody>
                                {shown.map((c) => {
                                    const expired = c.status === 'active' && c.expiresAt && c.expiresAt < Date.now()
                                    const soon = c.status === 'active' && c.expiresAt && c.expiresAt > Date.now() && c.expiresAt < Date.now() + 7 * 864e5
                                    return (
                                        <tr key={c.id} style={expired ? { background: 'rgba(255,50,80,0.04)' } : soon ? { background: '#fffbeb' } : undefined}>
                                            <td className="mono">{c.activationCode || c.id.slice(0, 8)}</td>
                                            <td><span className={`chip ${c.tier === 'premium' ? 'chip-premium' : 'chip-normal'}`}>{c.tier || 'normal'}</span></td>
                                            <td><StatusChip status={c.status} expiresAt={c.expiresAt} /></td>
                                            <td style={{ opacity: 0.72 }}>{fmtDate(c.expiresAt)}</td>
                                            <td style={{ textAlign: 'right' }}><button className="tbl-btn" disabled={busy || plans.length === 0} onClick={() => { setRenewPlanId(plans[0]?.id || ''); setRenewFor(c) }}>Renew</button></td>
                                        </tr>
                                    )
                                })}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>

            {renewFor && (
                <div style={{ position: 'fixed', inset: 0, background: 'rgba(32,30,29,0.72)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16, zIndex: 100 }} onClick={() => !busy && setRenewFor(null)}>
                    <div className="card" style={{ width: '100%', maxWidth: 460, padding: 32 }} onClick={(e) => e.stopPropagation()}>
                        <h3 style={{ fontSize: 18 }}>Renew client</h3>
                        <p style={{ fontSize: 13, opacity: 0.6, marginTop: 4 }}>Device <span className="mono">{renewFor.activationCode || renewFor.id.slice(0, 8)}</span></p>
                        <div className="field" style={{ marginTop: 16 }}><label>Plan</label><select className="input" value={renewPlanId} onChange={(e) => setRenewPlanId(e.target.value)}>{plans.map((p) => <option key={p.id} value={p.id}>{p.name} · {p.cost} cr · {p.months}mo</option>)}</select></div>
                        {renewPlan && (
                            <div style={{ marginTop: 14, borderTop: '2px solid var(--color-divider)', paddingTop: 14, fontSize: 13, display: 'flex', flexDirection: 'column', gap: 8 }}>
                                <Row label="Credit cost"><span style={{ fontFamily: 'Archivo', fontWeight: 800, color: 'var(--color-accent-700)' }}>{renewPlan.cost}</span></Row>
                                <Row label="Duration"><span style={{ fontWeight: 600 }}>{renewPlan.months} month(s)</span></Row>
                                <Row label="New expiry"><span style={{ fontWeight: 600 }}>{fmtDate(Math.max(Date.now(), renewFor.expiresAt || 0) + renewPlan.months * 30 * 864e5)}</span></Row>
                            </div>
                        )}
                        <div style={{ display: 'flex', gap: 12, marginTop: 24, justifyContent: 'flex-end' }}>
                            <button className="btn btn-secondary" disabled={busy} onClick={() => setRenewFor(null)}>Cancel</button>
                            <button className="btn btn-primary" disabled={busy || !renewPlanId} onClick={async () => { const ok = await call('renewClient', { installId: renewFor.id, planId: renewPlanId }); if (ok) setRenewFor(null) }}>{busy ? 'Renewing…' : 'Confirm renew'}</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    )
}
function Row({ label, children }: { label: string; children: React.ReactNode }) {
    return <div style={{ display: 'flex', justifyContent: 'space-between' }}><span style={{ opacity: 0.6 }}>{label}</span>{children}</div>
}

// ── Buy Credits ──
function BuyCredits({ packages, showToast }: { packages: Pkg[]; showToast: (t: string) => void }) {
    return (
        <div>
            <p style={{ fontSize: 14, opacity: 0.6, marginTop: 0 }}>Buy credits, then use them to activate and renew your clients.</p>
            {packages.length === 0 ? <div className="card" style={{ padding: 32, textAlign: 'center', fontSize: 13, opacity: 0.45 }}>No credit packages available yet.</div> : (
                <div className="grid3">
                    {packages.map((p) => (
                        <div key={p.id} className="cell" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                            <div style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em', opacity: 0.55 }}>{p.name}</div>
                            <div style={{ fontFamily: 'Archivo', fontWeight: 800, fontSize: 42, color: 'var(--color-accent)', lineHeight: 1 }}>{p.credits}<span style={{ fontSize: 15, opacity: 0.5 }}> cr</span></div>
                            {p.bonus ? <div style={{ fontSize: 12, color: 'var(--dx-success)' }}>+{p.bonus} bonus</div> : null}
                            <div style={{ fontSize: 15, fontWeight: 700 }}>${p.priceUsd.toFixed(2)}</div>
                            <button className="btn btn-primary btn-block" style={{ justifyContent: 'center', marginTop: 8 }} onClick={() => showToast('Checkout coming soon (PayPal).')}><CreditCard size={15} /> Buy</button>
                        </div>
                    ))}
                </div>
            )}
            <p style={{ fontSize: 12, opacity: 0.4, marginTop: 16, display: 'flex', alignItems: 'center', gap: 6 }}><ShieldCheck size={13} /> Payments are processed securely. Credits are added automatically once confirmed.</p>
        </div>
    )
}

// ── Billing ──
function Billing({ ledger }: { ledger: Ledger[] }) {
    return (
        <div>
            <div style={{ fontFamily: 'Archivo', fontWeight: 800, fontSize: 14, marginBottom: 14 }}>Credit history</div>
            <div className="card">
                {ledger.length === 0 ? <p style={{ padding: 28, textAlign: 'center', fontSize: 13, opacity: 0.45, margin: 0 }}>No transactions yet.</p> : (
                    <table className="table">
                        <thead><tr><th>Date</th><th>Type</th><th>Change</th><th>Balance</th></tr></thead>
                        <tbody>
                            {ledger.map((l) => (
                                <tr key={l.id}>
                                    <td style={{ opacity: 0.6 }}>{fmtDateTime(l.at)}</td>
                                    <td style={{ textTransform: 'capitalize' }}>{l.reason}</td>
                                    <td style={{ fontFamily: 'Archivo', fontWeight: 800, color: l.delta >= 0 ? 'var(--dx-success)' : 'inherit' }}>{l.delta >= 0 ? '+' : ''}{l.delta}</td>
                                    <td style={{ opacity: 0.7 }}>{l.balanceAfter ?? '—'}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    )
}

// ── Profile ──
function Profile({ uid, reseller, showToast }: { uid: string; reseller: ReturnType<typeof useAuth>['reseller']; showToast: (t: string) => void }) {
    const [form, setForm] = useState({ displayName: '', phone: '', country: '', contact: '' })
    const [pw, setPw] = useState({ current: '', next: '' })
    const [busy, setBusy] = useState(false)
    useEffect(() => { if (reseller) setForm({ displayName: reseller.displayName || '', phone: reseller.phone || '', country: reseller.country || '', contact: reseller.contact || '' }) }, [reseller])

    async function saveProfile() { setBusy(true); try { await setDoc(doc(db, 'resellers', uid), form, { merge: true }); showToast('Profile saved.') } catch (e) { showToast(errText(e)) } finally { setBusy(false) } }
    async function changePassword() {
        const u = auth.currentUser
        if (!u || !u.email) return
        if (pw.next.length < 6) { showToast('New password must be at least 6 characters.'); return }
        setBusy(true)
        try { await reauthenticateWithCredential(u, EmailAuthProvider.credential(u.email, pw.current)); await updatePassword(u, pw.next); setPw({ current: '', next: '' }); showToast('Password changed.') }
        catch (e) { showToast(errText(e)) } finally { setBusy(false) }
    }
    return (
        <div className="profile-grid">
            <div className="card" style={{ padding: 24 }}>
                <div style={{ fontFamily: 'Archivo', fontWeight: 800, fontSize: 14, marginBottom: 16 }}>Business information</div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                    <div className="field"><label>Business name</label><input className="input" value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} /></div>
                    <div className="field"><label>Phone</label><input className="input" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></div>
                    <div className="field"><label>Country</label><select className="input" value={form.country} onChange={(e) => setForm({ ...form, country: e.target.value })}><option value="">Select…</option>{COUNTRIES.map((c) => <option key={c} value={c}>{c}</option>)}</select></div>
                    <div className="field"><label>WhatsApp / Telegram</label><input className="input" value={form.contact} onChange={(e) => setForm({ ...form, contact: e.target.value })} /></div>
                    <button className="btn btn-primary" disabled={busy} onClick={saveProfile} style={{ alignSelf: 'flex-start' }}>Save profile</button>
                </div>
            </div>
            <div className="card" style={{ padding: 24 }}>
                <div style={{ fontFamily: 'Archivo', fontWeight: 800, fontSize: 14, marginBottom: 16 }}>Change password</div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                    <div className="field"><label>Current password</label><input className="input" type="password" value={pw.current} onChange={(e) => setPw({ ...pw, current: e.target.value })} /></div>
                    <div className="field"><label>New password</label><input className="input" type="password" value={pw.next} onChange={(e) => setPw({ ...pw, next: e.target.value })} /></div>
                    <button className="btn btn-secondary" disabled={busy} onClick={changePassword} style={{ alignSelf: 'flex-start' }}>Update password</button>
                </div>
            </div>
        </div>
    )
}

// ── shared ──
function StatusChip({ status, expiresAt }: { status?: string; expiresAt?: number }) {
    const now = Date.now()
    const expired = status === 'active' && expiresAt && expiresAt > 0 && expiresAt < now
    const soon = status === 'active' && expiresAt && expiresAt > now && expiresAt < now + 7 * 864e5
    const cls = expired ? 'chip-expired' : soon ? 'chip-expiring' : status === 'active' ? 'chip-active' : status === 'pending' ? 'chip-pending' : 'chip-inactive'
    const label = expired ? 'expired' : soon ? 'expiring' : status || 'unknown'
    return <span className={`chip ${cls}`}>{label}</span>
}
function fmtDate(ms?: number): string { return !ms || ms <= 0 ? '—' : new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) }
function fmtDateTime(ms?: number): string { return !ms || ms <= 0 ? '—' : new Date(ms).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }
