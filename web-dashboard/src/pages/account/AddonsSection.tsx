import { useEffect, useState } from 'react'
import { Pencil, Plus, Trash2 } from 'lucide-react'
import type { AccountDevice } from '../../account/devices'
import {
    createAddon, emptyAddonDraft, removeAddon, saveAddon, validateAddonDraft, watchAddons,
    type Addon, type AddonDraft,
} from '../../account/addons'
import { Field } from './accountUi'

/**
 * The customer's debrid links, on the same page as their IPTV details.
 *
 * They belong here because this is where somebody goes to set up what their devices play, and
 * because the alternative is where they had to go before: Settings on the television, typing a
 * hundred-character `manifest.json` URL with a remote control.
 *
 * A section of its own file rather than more of PlaylistsPage: the two lists share only the device
 * picker, and one screen file owning both editors is how a page becomes unreadable.
 */
export function AddonsSection({ ownerUid, devices, canEdit }: {
    ownerUid: string
    devices: AccountDevice[]
    /** False while the account is unverified — existing add-ons keep working, new ones wait. */
    canEdit: boolean
}) {
    const [rows, setRows] = useState<Addon[]>([])
    const [listError, setListError] = useState('')
    const [editingId, setEditingId] = useState<string | null>(null)
    const [draft, setDraft] = useState<AddonDraft | null>(null)
    const [error, setError] = useState('')
    const [busy, setBusy] = useState(false)

    useEffect(() => {
        return watchAddons(ownerUid, (r) => { setRows(r); setListError('') }, (e) => setListError(e.message))
    }, [ownerUid])

    function startAdd() { setEditingId('new'); setDraft(emptyAddonDraft()); setError('') }
    function startEdit(a: Addon) {
        setEditingId(a.id)
        setDraft({ name: a.name, kind: a.kind, url: a.url, enabled: a.enabled !== false, deviceId: a.deviceId || '' })
        setError('')
    }
    function cancel() { setEditingId(null); setDraft(null); setError('') }

    async function save() {
        if (!draft) return
        const problem = validateAddonDraft(draft)
        if (problem) { setError(problem); return }
        setBusy(true); setError('')
        try {
            if (editingId === 'new') await createAddon(ownerUid, draft)
            else if (editingId) await saveAddon(editingId, draft)
            cancel()
        } catch (e: unknown) {
            setError((e as { message?: string })?.message || 'Could not save. Please try again.')
        } finally { setBusy(false) }
    }

    async function confirmRemove(a: Addon) {
        if (!window.confirm(`Remove "${a.name}"? Devices using it will stop loading it.`)) return
        setListError('')
        try { await removeAddon(a.id) } catch (e: unknown) { setListError((e as { message?: string })?.message || 'Could not remove.') }
    }

    return (
        <div style={{ marginTop: 44 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
                <div>
                    <h2 style={{ margin: '0 0 4px' }}>Your debrid add-ons</h2>
                    <p style={{ margin: 0, fontSize: 14, opacity: 0.6 }}>Paste the links here instead of typing them on your TV. Add as many as you like.</p>
                </div>
                {canEdit && !editingId && (
                    <button onClick={startAdd} className="btn btn-primary" style={{ display: 'flex', alignItems: 'center', gap: 7 }}><Plus size={15} />Add link</button>
                )}
            </div>

            {listError && <p style={{ color: 'var(--dx-danger)', fontSize: 13 }}>{listError}</p>}

            {editingId && draft && (
                <div className="card" style={{ marginTop: 20, padding: 24 }}>
                    <h3 style={{ margin: '0 0 16px', fontSize: 16 }}>{editingId === 'new' ? 'Add a link' : 'Edit link'}</h3>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                        <Field label="Name"><input className="input" value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="My add-on" /></Field>
                        <Field label="Kind">
                            <select className="input" value={draft.kind} onChange={(e) => setDraft({ ...draft, kind: e.target.value as AddonDraft['kind'] })}>
                                <option value="addon">One add-on (link ends in manifest.json)</option>
                                <option value="registry">Add-on list (one link, many add-ons)</option>
                            </select>
                        </Field>
                        <Field label="Link">
                            <input
                                className="input"
                                value={draft.url}
                                onChange={(e) => setDraft({ ...draft, url: e.target.value })}
                                placeholder={draft.kind === 'addon' ? 'https://addon.example/config/manifest.json' : 'https://example.com/addons.json'}
                                autoComplete="off"
                                spellCheck={false}
                            />
                        </Field>
                        <Field label="Use it on">
                            <select className="input" value={draft.deviceId || ''} onChange={(e) => setDraft({ ...draft, deviceId: e.target.value })}>
                                <option value="">All my devices</option>
                                {devices.map((d) => (
                                    <option key={d.installId} value={d.installId}>{d.deviceName || d.activationCode || d.installId.slice(0, 8)}</option>
                                ))}
                            </select>
                        </Field>
                        <label style={{ display: 'flex', alignItems: 'center', gap: 9, fontSize: 14 }}>
                            <input type="checkbox" checked={draft.enabled} onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })} />
                            Use this link on my devices
                        </label>
                        {error && <p style={{ color: 'var(--dx-danger)', fontSize: 13, margin: 0 }}>{error}</p>}
                        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                            <button onClick={save} disabled={busy} className="btn btn-primary">{busy ? 'Saving…' : 'Save'}</button>
                            <button onClick={cancel} className="btn btn-ghost" style={{ opacity: 0.6 }}>Cancel</button>
                        </div>
                    </div>
                </div>
            )}

            <div style={{ display: 'grid', gap: 12, marginTop: 20 }}>
                {rows.length === 0 && !editingId && (
                    <div className="card" style={{ padding: 28, textAlign: 'center' }}>
                        <p style={{ margin: 0, fontSize: 14, opacity: 0.6 }}>No add-ons yet. Paste a link here and every device on your account picks it up.</p>
                    </div>
                )}
                {rows.map((a) => (
                    <div key={a.id} className="card" style={{ padding: '16px 20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14 }}>
                        <div style={{ minWidth: 0 }}>
                            <div style={{ fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
                                {a.name}
                                {a.kind === 'registry' && <span className="chip" style={{ fontSize: 11, opacity: 0.7 }}>LIST</span>}
                                {a.enabled === false && <span className="chip" style={{ fontSize: 11, opacity: 0.7 }}>OFF</span>}
                            </div>
                            {/* Host only, matching the playlist rows: an add-on link can carry a debrid API
                                key in its path, and a living room is a public place. */}
                            <div className="mono" style={{ fontSize: 12, opacity: 0.5, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                {safeHost(a.url)} · {addonDeviceLabel(a.deviceId, devices)}
                            </div>
                        </div>
                        <div style={{ display: 'flex', gap: 6, flex: 'none' }}>
                            <button onClick={() => startEdit(a)} disabled={!canEdit} className="btn btn-ghost" title="Edit"><Pencil size={15} /></button>
                            <button onClick={() => confirmRemove(a)} disabled={!canEdit} className="btn btn-ghost" title="Remove" style={{ color: 'var(--dx-danger)' }}><Trash2 size={15} /></button>
                        </div>
                    </div>
                ))}
            </div>
        </div>
    )
}

function safeHost(url: string): string {
    try { return new URL(url).host } catch { return url }
}

/** Same wording as the playlist rows, for the same reason — see deviceLabel there. */
export function addonDeviceLabel(deviceId: string | undefined, devices: AccountDevice[]): string {
    if (!deviceId) return 'all devices'
    const d = devices.find((x) => x.installId === deviceId)
    return d ? (d.deviceName || d.activationCode || d.installId.slice(0, 8)) : 'removed device'
}
