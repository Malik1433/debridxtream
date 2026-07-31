import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AlertTriangle, ArrowLeft, Pencil, Plus, Trash2 } from 'lucide-react'
import { useAccount } from '../../account/useAccount'
import { verificationGrace } from '../../account/verificationGrace'
import {
    createPlaylist, emptyDraft, removePlaylist, savePlaylist, testPlaylist, validateDraft,
    watchPlaylists, type Playlist, type PlaylistDraft,
} from '../../account/playlists'
import { DxLogo } from '../reseller/authUi'
import { Field } from './accountUi'

export default function PlaylistsPage() {
    const nav = useNavigate()
    const { user, profile, loading } = useAccount()
    const [rows, setRows] = useState<Playlist[]>([])
    const [listError, setListError] = useState('')
    const [editingId, setEditingId] = useState<string | null>(null)
    const [draft, setDraft] = useState<PlaylistDraft | null>(null)
    const [error, setError] = useState('')
    const [testMsg, setTestMsg] = useState('')
    const [busy, setBusy] = useState(false)
    const [testing, setTesting] = useState(false)

    useEffect(() => {
        if (!loading && !user) nav('/account/login', { replace: true })
    }, [loading, user, nav])

    useEffect(() => {
        if (!user) return
        // Clearing the error on every good snapshot matters: without it a message from an earlier
        // failure stays on screen after the next action succeeds, telling the customer something
        // went wrong when it didn't.
        return watchPlaylists(user.uid, (r) => { setRows(r); setListError('') }, (e) => setListError(e.message))
    }, [user])

    if (loading || !user) {
        return <div className="mod" style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', opacity: 0.5 }}>Loading…</div>
    }

    const createdAt = (profile?.createdAt as unknown as { toMillis?: () => number })?.toMillis?.()
    const grace = verificationGrace(Date.now(), user.emailVerified, createdAt)

    function startAdd() { setEditingId('new'); setDraft(emptyDraft()); setError(''); setTestMsg('') }
    function startEdit(p: Playlist) {
        setEditingId(p.id)
        setDraft({ name: p.name, type: p.type, url: p.url, username: p.username, password: p.password, enabled: p.enabled !== false })
        setError(''); setTestMsg('')
    }
    function cancel() { setEditingId(null); setDraft(null); setError(''); setTestMsg('') }

    async function runTest() {
        if (!draft) return
        const problem = validateDraft(draft)
        if (problem) { setError(problem); return }
        setError(''); setTesting(true); setTestMsg('')
        const r = await testPlaylist(draft)
        setTestMsg((r.ok ? '✓ ' : '✕ ') + r.message)
        setTesting(false)
    }

    async function save() {
        if (!draft || !user) return
        const problem = validateDraft(draft)
        if (problem) { setError(problem); return }
        setBusy(true); setError('')
        try {
            if (editingId === 'new') await createPlaylist(user.uid, draft)
            else if (editingId) await savePlaylist(editingId, draft)
            cancel()
        } catch (e: unknown) {
            setError((e as { message?: string })?.message || 'Could not save. Please try again.')
        } finally { setBusy(false) }
    }

    async function confirmRemove(p: Playlist) {
        if (!window.confirm(`Remove "${p.name}"? Devices using it will stop loading it.`)) return
        setListError('')
        try { await removePlaylist(p.id) } catch (e: unknown) { setListError((e as { message?: string })?.message || 'Could not remove.') }
    }

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
                        <h2 style={{ margin: '0 0 4px' }}>Your playlists</h2>
                        <p style={{ margin: 0, fontSize: 14, opacity: 0.6 }}>Add your IPTV details once — every device on your account picks them up.</p>
                    </div>
                    {grace.allowed && !editingId && (
                        <button onClick={startAdd} className="btn btn-primary" style={{ display: 'flex', alignItems: 'center', gap: 7 }}><Plus size={15} />Add playlist</button>
                    )}
                </div>

                {!grace.allowed && (
                    <div className="card" style={{ marginTop: 20, padding: '14px 18px', display: 'flex', gap: 12, alignItems: 'flex-start', background: 'var(--color-accent-100)', borderColor: 'var(--color-accent-300)' }}>
                        <AlertTriangle size={18} color="#B45309" style={{ flex: 'none', marginTop: 2 }} />
                        <div style={{ fontSize: 14 }}>
                            <b>Verify your email to add or change playlists.</b> Your existing playlists keep working.
                            <div style={{ marginTop: 10 }}><button onClick={() => nav('/account/verify')} className="btn btn-secondary" style={{ fontSize: 13 }}>Verify now</button></div>
                        </div>
                    </div>
                )}

                {listError && <p style={{ color: 'var(--dx-danger)', fontSize: 13 }}>{listError}</p>}

                {editingId && draft && (
                    <div className="card" style={{ marginTop: 20, padding: 24 }}>
                        <h3 style={{ margin: '0 0 16px', fontSize: 16 }}>{editingId === 'new' ? 'Add a playlist' : 'Edit playlist'}</h3>
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                            <Field label="Name"><input className="input" value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="Living room provider" /></Field>
                            <Field label="Server address"><input className="input" value={draft.url} onChange={(e) => setDraft({ ...draft, url: e.target.value })} placeholder="http://your-provider.com:8080" /></Field>
                            <Field label="Username"><input className="input" value={draft.username} onChange={(e) => setDraft({ ...draft, username: e.target.value })} autoComplete="off" /></Field>
                            <Field label="Password"><input className="input" type="password" value={draft.password} onChange={(e) => setDraft({ ...draft, password: e.target.value })} autoComplete="new-password" /></Field>
                            <label style={{ display: 'flex', alignItems: 'center', gap: 9, fontSize: 14 }}>
                                <input type="checkbox" checked={draft.enabled} onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })} />
                                Use this playlist on my devices
                            </label>
                            {error && <p style={{ color: 'var(--dx-danger)', fontSize: 13, margin: 0 }}>{error}</p>}
                            {testMsg && <p style={{ fontSize: 13, margin: 0, color: testMsg.startsWith('✓') ? 'var(--dx-success)' : 'var(--dx-danger)' }}>{testMsg}</p>}
                            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                                <button onClick={save} disabled={busy} className="btn btn-primary">{busy ? 'Saving…' : 'Save'}</button>
                                <button onClick={runTest} disabled={testing} className="btn btn-secondary">{testing ? 'Testing…' : 'Test connection'}</button>
                                <button onClick={cancel} className="btn btn-ghost" style={{ opacity: 0.6 }}>Cancel</button>
                            </div>
                        </div>
                    </div>
                )}

                <div style={{ display: 'grid', gap: 12, marginTop: 20 }}>
                    {rows.length === 0 && !editingId && (
                        <div className="card" style={{ padding: 28, textAlign: 'center' }}>
                            <p style={{ margin: 0, fontSize: 14, opacity: 0.6 }}>No playlists yet. Add the details your IPTV provider gave you.</p>
                        </div>
                    )}
                    {rows.map((p) => (
                        <div key={p.id} className="card" style={{ padding: '16px 20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14 }}>
                            <div style={{ minWidth: 0 }}>
                                <div style={{ fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
                                    {p.name}
                                    {p.enabled === false && <span className="chip" style={{ fontSize: 11, opacity: 0.7 }}>OFF</span>}
                                </div>
                                {/* Host only — the full URL carries nothing secret, but showing it in a list
                                    invites shoulder-surfing in a living room. The password is never rendered. */}
                                <div className="mono" style={{ fontSize: 12, opacity: 0.5, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                    {safeHost(p.url)} · {p.username}
                                </div>
                            </div>
                            <div style={{ display: 'flex', gap: 6, flex: 'none' }}>
                                <button onClick={() => startEdit(p)} disabled={!grace.allowed} className="btn btn-ghost" title="Edit"><Pencil size={15} /></button>
                                <button onClick={() => confirmRemove(p)} disabled={!grace.allowed} className="btn btn-ghost" title="Remove" style={{ color: 'var(--dx-danger)' }}><Trash2 size={15} /></button>
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        </div>
    )
}

function safeHost(url: string): string {
    try { return new URL(url).host } catch { return url }
}
