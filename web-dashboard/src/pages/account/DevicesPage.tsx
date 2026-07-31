import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft, Check, Pencil, Plus, Tv } from 'lucide-react'
import { useAccount } from '../../account/useAccount'
import { lastSeenLabel, renameDevice, watchDevices, type AccountDevice } from '../../account/devices'
import { DxLogo } from '../reseller/authUi'

export default function DevicesPage() {
    const nav = useNavigate()
    const { user, loading } = useAccount()
    const [rows, setRows] = useState<AccountDevice[]>([])
    const [listError, setListError] = useState('')
    const [editingId, setEditingId] = useState<string | null>(null)
    const [draftName, setDraftName] = useState('')
    const [busy, setBusy] = useState(false)

    useEffect(() => {
        if (!loading && !user) nav('/account/login', { replace: true })
    }, [loading, user, nav])

    useEffect(() => {
        if (!user) return
        return watchDevices(user.uid, (r) => { setRows(r); setListError('') }, (e) => setListError(e.message))
    }, [user])

    if (loading || !user) {
        return <div className="mod" style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', opacity: 0.5 }}>Loading…</div>
    }

    async function saveName(d: AccountDevice) {
        setBusy(true)
        try { await renameDevice(d.installId, draftName); setEditingId(null) }
        catch (e: unknown) { setListError((e as { message?: string })?.message || 'Could not rename.') }
        finally { setBusy(false) }
    }

    const now = Date.now()

    return (
        <div className="mod" style={{ minHeight: '100vh' }}>
            <div style={{ borderBottom: '1px solid var(--color-neutral-200)', padding: '16px 24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <DxLogo />
                <button onClick={() => nav('/account')} className="btn btn-ghost" style={{ fontSize: 13, opacity: 0.6, display: 'flex', alignItems: 'center', gap: 6 }}><ArrowLeft size={14} />Account</button>
            </div>

            <div style={{ maxWidth: 760, margin: '0 auto', padding: '32px 24px' }}>
                <div className="kicker" style={{ marginBottom: 8 }}>My Account</div>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
                    <div>
                        <h2 style={{ margin: '0 0 4px' }}>Your devices</h2>
                        <p style={{ margin: 0, fontSize: 14, opacity: 0.6 }}>The TVs linked to this account.</p>
                    </div>
                    <button onClick={() => nav('/link')} className="btn btn-primary" style={{ display: 'flex', alignItems: 'center', gap: 7 }}><Plus size={15} />Add a TV</button>
                </div>

                {listError && <p style={{ color: 'var(--dx-danger)', fontSize: 13 }}>{listError}</p>}

                <div style={{ display: 'grid', gap: 12, marginTop: 20 }}>
                    {rows.length === 0 && (
                        <div className="card" style={{ padding: 28, textAlign: 'center' }}>
                            <p style={{ margin: 0, fontSize: 14, opacity: 0.6 }}>No TVs yet. Open the app on your TV and add it with the code on screen.</p>
                        </div>
                    )}
                    {rows.map((d) => (
                        <div key={d.installId} className="card" style={{ padding: '16px 20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14 }}>
                            <div style={{ display: 'flex', gap: 14, alignItems: 'center', minWidth: 0 }}>
                                <Tv size={18} style={{ flex: 'none', opacity: 0.45 }} />
                                <div style={{ minWidth: 0 }}>
                                    {editingId === d.installId ? (
                                        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                                            <input className="input" value={draftName} onChange={(e) => setDraftName(e.target.value)} placeholder="Living room" style={{ width: 200 }} autoFocus />
                                            <button onClick={() => saveName(d)} disabled={busy} className="btn btn-primary" style={{ padding: '6px 12px' }}><Check size={14} /></button>
                                            <button onClick={() => setEditingId(null)} className="btn btn-ghost" style={{ opacity: 0.6, fontSize: 13 }}>Cancel</button>
                                        </div>
                                    ) : (
                                        <div style={{ fontWeight: 600 }}>{d.deviceName || 'Unnamed TV'}</div>
                                    )}
                                    <div className="mono" style={{ fontSize: 12, opacity: 0.5, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                        {d.activationCode || d.installId.slice(0, 8)} · {lastSeenLabel(d.lastSeenAt, now)}
                                    </div>
                                </div>
                            </div>
                            {editingId !== d.installId && (
                                <button onClick={() => { setEditingId(d.installId); setDraftName(d.deviceName || '') }} className="btn btn-ghost" title="Rename"><Pencil size={15} /></button>
                            )}
                        </div>
                    ))}
                </div>

                {/* Said once, plainly, instead of showing a Remove button that would only ever fail.
                    The owner chose staff-only slot release (§7.8) precisely so three devices means
                    three — the honest cost is that a replaced TV needs a message to the provider. */}
                {rows.length > 0 && (
                    <p style={{ marginTop: 20, fontSize: 13, opacity: 0.55 }}>
                        Replacing a TV? Ask your provider to remove the old one — that frees the slot for a new device.
                    </p>
                )}
            </div>
        </div>
    )
}
