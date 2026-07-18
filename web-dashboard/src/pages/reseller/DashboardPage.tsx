import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { signOut } from 'firebase/auth'
import { collection, onSnapshot, query, where } from 'firebase/firestore'
import { httpsCallable } from 'firebase/functions'
import { LogOut, Plus, RefreshCw, Ticket } from 'lucide-react'
import { auth, db, functions } from '../../firebase'
import { useAuth } from '../../reseller/useAuth'
import { errText } from './SignupPage'

interface Plan { id: string; name: string; tier: string; months: number; cost: number }
interface Client {
    id: string
    activationCode?: string
    tier?: string
    status?: string
    expiresAt?: number
    planId?: string
}

export default function DashboardPage() {
    const nav = useNavigate()
    const { user, reseller, loading } = useAuth()
    const [plans, setPlans] = useState<Plan[]>([])
    const [clients, setClients] = useState<Client[]>([])
    const [busy, setBusy] = useState(false)
    const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null)
    const [code, setCode] = useState('')
    const [planId, setPlanId] = useState('')

    useEffect(() => {
        if (!loading && !user) nav('/reseller/login', { replace: true })
    }, [loading, user, nav])

    // Plans catalogue (owner-defined).
    useEffect(() => {
        return onSnapshot(collection(db, 'plans'), (snap) => {
            const list = snap.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<Plan, 'id'>) }))
            list.sort((a, b) => a.cost - b.cost)
            setPlans(list)
            setPlanId((cur) => cur || list[0]?.id || '')
        })
    }, [])

    // This reseller's clients.
    useEffect(() => {
        if (!user) return
        const q = query(collection(db, 'licenses'), where('resellerId', '==', user.uid))
        return onSnapshot(q, (snap) => {
            const list = snap.docs.map((d) => ({ id: d.id, ...(d.data() as Omit<Client, 'id'>) }))
            list.sort((a, b) => (b.expiresAt || 0) - (a.expiresAt || 0))
            setClients(list)
        }, () => {/* permission errors surface elsewhere */})
    }, [user])

    async function activate(e: React.FormEvent) {
        e.preventDefault()
        setMsg(null)
        if (!code.trim() || !planId) return
        setBusy(true)
        try {
            const fn = httpsCallable(functions, 'activateClient')
            const res = await fn({ activationCode: code.trim(), planId })
            const data = res.data as { creditsLeft?: number }
            setMsg({ kind: 'ok', text: `Activated. Credits left: ${data?.creditsLeft ?? '—'}.` })
            setCode('')
        } catch (err) {
            setMsg({ kind: 'err', text: errText(err) })
        } finally {
            setBusy(false)
        }
    }

    async function renew(installId: string) {
        if (!planId) { setMsg({ kind: 'err', text: 'Pick a plan first.' }); return }
        setBusy(true)
        setMsg(null)
        try {
            const fn = httpsCallable(functions, 'renewClient')
            const res = await fn({ installId, planId })
            const data = res.data as { creditsLeft?: number }
            setMsg({ kind: 'ok', text: `Renewed. Credits left: ${data?.creditsLeft ?? '—'}.` })
        } catch (err) {
            setMsg({ kind: 'err', text: errText(err) })
        } finally {
            setBusy(false)
        }
    }

    const selectedPlan = useMemo(() => plans.find((p) => p.id === planId), [plans, planId])

    if (loading) return <div className="grid min-h-screen place-items-center text-neutral-400">Loading…</div>

    return (
        <div className="mx-auto max-w-4xl px-5 py-8">
            {/* header */}
            <div className="mb-8 flex items-center justify-between">
                <div className="flex items-center gap-3">
                    <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gold-500 font-black text-black">DX</div>
                    <div>
                        <p className="text-sm font-semibold">{reseller?.displayName || user?.email}</p>
                        <p className="text-xs text-neutral-400">Reseller dashboard</p>
                    </div>
                </div>
                <div className="flex items-center gap-3">
                    <div className="rounded-xl border border-gold-500/30 bg-gold-500/10 px-4 py-2 text-right">
                        <p className="text-[10px] uppercase tracking-wide text-gold-300/80">Credits</p>
                        <p className="text-lg font-bold text-gold-300">{reseller?.credits ?? 0}</p>
                    </div>
                    <button onClick={() => signOut(auth)} className="rounded-lg border border-white/10 p-2.5 text-neutral-300 hover:bg-white/5" title="Sign out">
                        <LogOut size={16} />
                    </button>
                </div>
            </div>

            {user && !user.emailVerified && (
                <div className="mb-5 rounded-lg border border-amber-500/30 bg-amber-500/10 px-4 py-2.5 text-sm text-amber-200">
                    Please verify your email — we sent you a link.
                </div>
            )}

            {/* activate a client */}
            <div className="mb-6 rounded-2xl border border-white/10 bg-neutral-900/60 p-5">
                <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold"><Ticket size={16} /> Add a client</h2>
                <form onSubmit={activate} className="flex flex-col gap-3 sm:flex-row sm:items-end">
                    <label className="flex-1">
                        <span className="mb-1.5 block text-xs uppercase tracking-wide text-neutral-400">Activation code (from the TV)</span>
                        <input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="XXXX-XXXX"
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-3.5 py-2.5 font-mono text-sm text-white placeholder:text-neutral-500 outline-none focus:border-gold-500/60" />
                    </label>
                    <label className="sm:w-56">
                        <span className="mb-1.5 block text-xs uppercase tracking-wide text-neutral-400">Plan</span>
                        <select value={planId} onChange={(e) => setPlanId(e.target.value)}
                            className="w-full rounded-lg border border-white/10 bg-white/5 px-3.5 py-2.5 text-sm text-white outline-none focus:border-gold-500/60">
                            {plans.length === 0 && <option value="">No plans yet</option>}
                            {plans.map((p) => (
                                <option key={p.id} value={p.id}>{p.name} · {p.cost} cr</option>
                            ))}
                        </select>
                    </label>
                    <button disabled={busy || !code.trim() || !planId}
                        className="inline-flex items-center justify-center gap-1.5 rounded-lg bg-gold-500 px-4 py-2.5 text-sm font-semibold text-black transition hover:bg-gold-400 disabled:opacity-50">
                        <Plus size={16} /> Activate
                    </button>
                </form>
                {selectedPlan && (
                    <p className="mt-2 text-xs text-neutral-400">
                        {selectedPlan.tier === 'premium' ? 'Premium (IPTV + Debrid)' : 'Normal (IPTV)'} · {selectedPlan.months} month(s) · costs {selectedPlan.cost} credits
                    </p>
                )}
                {msg && (
                    <p className={`mt-3 text-sm ${msg.kind === 'ok' ? 'text-emerald-400' : 'text-red-400'}`}>{msg.text}</p>
                )}
            </div>

            {/* client list */}
            <div className="rounded-2xl border border-white/10 bg-neutral-900/60">
                <div className="border-b border-white/10 px-5 py-3 text-sm font-semibold">Your clients ({clients.length})</div>
                {clients.length === 0 ? (
                    <p className="px-5 py-8 text-center text-sm text-neutral-500">No clients yet. Enter a device's activation code above.</p>
                ) : (
                    <div className="overflow-x-auto">
                        <table className="w-full text-sm">
                            <thead className="text-left text-xs uppercase tracking-wide text-neutral-500">
                                <tr className="border-b border-white/5">
                                    <th className="px-5 py-2.5 font-medium">Code</th>
                                    <th className="px-3 py-2.5 font-medium">Tier</th>
                                    <th className="px-3 py-2.5 font-medium">Status</th>
                                    <th className="px-3 py-2.5 font-medium">Expires</th>
                                    <th className="px-5 py-2.5" />
                                </tr>
                            </thead>
                            <tbody>
                                {clients.map((c) => (
                                    <tr key={c.id} className="border-b border-white/5 last:border-0">
                                        <td className="px-5 py-3 font-mono">{c.activationCode || c.id.slice(0, 8)}</td>
                                        <td className="px-3 py-3">
                                            <span className={c.tier === 'premium' ? 'text-gold-300' : 'text-neutral-300'}>{c.tier || '—'}</span>
                                        </td>
                                        <td className="px-3 py-3"><StatusPill status={c.status} expiresAt={c.expiresAt} /></td>
                                        <td className="px-3 py-3 text-neutral-300">{fmtDate(c.expiresAt)}</td>
                                        <td className="px-5 py-3 text-right">
                                            <button onClick={() => renew(c.id)} disabled={busy || !planId}
                                                className="inline-flex items-center gap-1 rounded-md border border-white/10 px-2.5 py-1.5 text-xs text-neutral-200 hover:bg-white/5 disabled:opacity-50">
                                                <RefreshCw size={13} /> Renew
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>
        </div>
    )
}

function StatusPill({ status, expiresAt }: { status?: string; expiresAt?: number }) {
    const expired = status === 'active' && expiresAt && expiresAt > 0 && expiresAt < Date.now()
    const label = expired ? 'expired' : status || 'unknown'
    const cls = expired
        ? 'bg-red-500/15 text-red-300'
        : status === 'active'
            ? 'bg-emerald-500/15 text-emerald-300'
            : status === 'pending'
                ? 'bg-amber-500/15 text-amber-300'
                : 'bg-white/10 text-neutral-300'
    return <span className={`rounded-full px-2 py-0.5 text-xs ${cls}`}>{label}</span>
}

function fmtDate(ms?: number): string {
    if (!ms || ms <= 0) return '—'
    return new Date(ms).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}
