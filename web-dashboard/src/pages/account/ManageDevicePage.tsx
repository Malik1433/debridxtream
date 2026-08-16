import { useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { ArrowLeft, Tv } from 'lucide-react'
import { useAccount } from '../../account/useAccount'
import { verificationGrace } from '../../account/verificationGrace'
import { lastSeenLabel, watchDevices, type AccountDevice } from '../../account/devices'
import { watchPlaylists, type Playlist } from '../../account/playlists'
import { watchAddons, type Addon } from '../../account/addons'
import { DxLogo } from '../reseller/authUi'
import { AddonsSection } from './AddonsSection'

/**
 * What ONE device is actually running, reached by scanning the QR in its Settings.
 *
 * The account pages answer "what have I saved"; this one answers the question somebody standing in
 * front of a television actually has — "what is THIS box using, and how do I change it". Those are
 * different questions: a playlist addressed to another device, or an add-on that is switched off,
 * belongs on the account page and is noise here.
 *
 * Signing in is only asked for when it is needed, which is the owner's decision: already signed in
 * on the phone goes straight through; otherwise sign in and come back, exactly as `/link` does.
 */
export default function ManageDevicePage() {
    const nav = useNavigate()
    const { search } = useLocation()
    const [params] = useSearchParams()
    const { user, profile, loading } = useAccount()

    const deviceKey = normalizeKey(params.get('device') || '')
    const [devices, setDevices] = useState<AccountDevice[]>([])
    const [playlists, setPlaylists] = useState<Playlist[]>([])
    const [addons, setAddons] = useState<Addon[]>([])
    const [listError, setListError] = useState('')

    useEffect(() => {
        if (loading || user) return
        const p = new URLSearchParams(search)
        if (!p.get('next')) p.set('next', `/account/manage${search || ''}`)
        nav(`/account/login?${p.toString()}`, { replace: true })
    }, [loading, user, nav, search])

    useEffect(() => {
        if (!user) return
        return watchDevices(user.uid, setDevices, (e) => setListError(e.message))
    }, [user])

    useEffect(() => {
        if (!user) return
        return watchPlaylists(user.uid, (r) => { setPlaylists(r); setListError('') }, (e) => setListError(e.message))
    }, [user])

    useEffect(() => {
        if (!user) return
        return watchAddons(user.uid, setAddons, (e) => setListError(e.message))
    }, [user])

    // Matched on the activation code the QR carries, falling back to the installId — the code is
    // what the customer can also read off their screen and type, so both have to resolve.
    const device = useMemo(
        () => devices.find((d) => normalizeKey(d.activationCode || '') === deviceKey || d.installId === params.get('device')),
        [devices, deviceKey, params],
    )

    if (loading || !user) {
        return <div className="mod" style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', opacity: 0.5 }}>Loading…</div>
    }

    const createdAt = (profile?.createdAt as unknown as { toMillis?: () => number })?.toMillis?.()
    const grace = verificationGrace(Date.now(), user.emailVerified, createdAt)

    const appliesHere = (assignedTo: string | undefined) => !assignedTo || assignedTo === device?.installId
    const devicePlaylists = playlists.filter((p) => appliesHere(p.deviceId))
    const deviceAddons = addons.filter((a) => appliesHere(a.deviceId) && a.enabled !== false)

    return (
        <div className="mod" style={{ minHeight: '100vh' }}>
            <div style={{ borderBottom: '1px solid var(--color-neutral-200)', padding: '16px 24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <DxLogo />
                <button onClick={() => nav('/account')} className="btn btn-ghost" style={{ fontSize: 13, opacity: 0.6, display: 'flex', alignItems: 'center', gap: 6 }}><ArrowLeft size={14} />Account</button>
            </div>

            <div style={{ maxWidth: 760, margin: '0 auto', padding: '32px 24px' }}>
                <div className="kicker" style={{ marginBottom: 8 }}>My Account</div>

                {!device ? (
                    <div className="card" style={{ padding: 28 }}>
                        <h2 style={{ margin: '0 0 6px' }}>That device isn’t on your account</h2>
                        <p style={{ margin: '0 0 18px', fontSize: 14, opacity: 0.7 }}>
                            {deviceKey
                                ? <>The code <span className="mono">{deviceKey}</span> doesn’t match any device here. If you have just set this one up, add it first.</>
                                : <>Open this page by scanning the code in your device’s Settings, or pick a device from your account.</>}
                        </p>
                        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                            <button onClick={() => nav(`/link?code=${encodeURIComponent(deviceKey)}`)} className="btn btn-primary">Add this device</button>
                            <button onClick={() => nav('/account/devices')} className="btn btn-ghost" style={{ opacity: 0.6 }}>My devices</button>
                        </div>
                    </div>
                ) : (
                    <>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 4 }}>
                            <Tv size={20} style={{ opacity: 0.6 }} />
                            <h2 style={{ margin: 0 }}>{device.deviceName || 'This device'}</h2>
                        </div>
                        <p style={{ margin: '0 0 8px', fontSize: 14, opacity: 0.6 }}>
                            <span className="mono">{device.activationCode || device.installId.slice(0, 8)}</span> · {lastSeenLabel(device.lastSeenAt, Date.now())}
                        </p>
                        <p style={{ margin: '0 0 24px', fontSize: 14, opacity: 0.6 }}>
                            This is what it is using right now. Change anything here and it updates on the device on its own.
                        </p>

                        {listError && <p style={{ color: 'var(--dx-danger)', fontSize: 13 }}>{listError}</p>}

                        <h3 style={{ margin: '0 0 10px', fontSize: 16 }}>Its IPTV playlist</h3>
                        <div style={{ display: 'grid', gap: 12 }}>
                            {devicePlaylists.length === 0 && (
                                <div className="card" style={{ padding: 24, textAlign: 'center' }}>
                                    <p style={{ margin: '0 0 14px', fontSize: 14, opacity: 0.6 }}>No playlist reaches this device yet.</p>
                                    <button onClick={() => nav('/account/playlists')} className="btn btn-primary">Add your IPTV details</button>
                                </div>
                            )}
                            {devicePlaylists.map((p) => (
                                <div key={p.id} className="card" style={{ padding: '16px 20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14 }}>
                                    <div style={{ minWidth: 0 }}>
                                        <div style={{ fontWeight: 600 }}>{p.name}{p.enabled === false && <span className="chip" style={{ fontSize: 11, marginLeft: 8, opacity: 0.7 }}>OFF</span>}</div>
                                        <div className="mono" style={{ fontSize: 12, opacity: 0.5, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                            {safeHost(p.url)} · {p.username}
                                        </div>
                                    </div>
                                    <button onClick={() => nav('/account/playlists')} className="btn btn-secondary" style={{ flex: 'none', fontSize: 13 }}>Change</button>
                                </div>
                            ))}
                            {devicePlaylists.length > 1 && (
                                // Worth saying out loud: the device runs ONE of these, and which one is not
                                // obvious from a list. Silence here reads as "all of them are playing".
                                <p style={{ margin: 0, fontSize: 12, opacity: 0.55 }}>
                                    This device runs one playlist at a time — the one assigned to it, or the first that applies to all devices.
                                </p>
                            )}
                        </div>

                        {/* The same editor as the account page, so there is one place these rules live and
                            one behaviour to learn. It shows the whole account's add-ons, which is right:
                            adding one here should be able to reach the other devices too. */}
                        <AddonsSection ownerUid={user.uid} devices={devices} canEdit={grace.allowed} />

                        <p style={{ marginTop: 18, fontSize: 12, opacity: 0.55 }}>
                            {deviceAddons.length === 0
                                ? 'No add-ons reach this device yet.'
                                : `${deviceAddons.length} add-on${deviceAddons.length === 1 ? '' : 's'} currently reach this device.`}
                        </p>
                    </>
                )}
            </div>
        </div>
    )
}

function safeHost(url: string): string {
    try { return new URL(url).host } catch { return url }
}

/** Device codes are XXXX-XXXX; accept them with or without the dash, in any case. */
function normalizeKey(raw: string): string {
    const compact = raw.toUpperCase().replace(/[^A-Z0-9]/g, '')
    return compact.length === 8 ? `${compact.slice(0, 4)}-${compact.slice(4)}` : compact
}
