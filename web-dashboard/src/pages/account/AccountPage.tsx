import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { signOut } from 'firebase/auth'
import { AlertTriangle, MonitorSmartphone, ListVideo } from 'lucide-react'
import { auth } from '../../firebase'
import { useAccount } from '../../account/useAccount'
import { verificationGrace } from '../../account/verificationGrace'
import { DxLogo } from '../reseller/authUi'
import { withSearch } from './accountUi'

/**
 * The signed-in customer's home.
 *
 * U1 deliberately ships it near-empty: devices (U7) and playlists (U2) are separate phases, and
 * putting placeholder controls here that do nothing would be worse than an honest empty state.
 */
export default function AccountPage() {
    const nav = useNavigate()
    const { search } = useLocation()
    const { user, profile, loading } = useAccount()

    useEffect(() => {
        if (!loading && !user) nav(withSearch('/account/login', search), { replace: true })
    }, [loading, user, nav, search])

    if (loading || !user) {
        return <div className="mod" style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', opacity: 0.5 }}>Loading…</div>
    }

    // Firestore serverTimestamp arrives as a Timestamp; it is momentarily null in the local echo
    // right after signup, which reads as "unknown" — treated leniently, see verificationGrace().
    const createdAt = (profile?.createdAt as unknown as { toMillis?: () => number })?.toMillis?.()
    const grace = verificationGrace(Date.now(), user.emailVerified, createdAt)

    return (
        <div className="mod" style={{ minHeight: '100vh' }}>
            <div style={{ borderBottom: '1px solid var(--color-neutral-200)', padding: '16px 24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <DxLogo />
                <button onClick={() => signOut(auth).then(() => nav('/account/login'))} className="btn btn-ghost" style={{ fontSize: 13, opacity: 0.6 }}>Sign out</button>
            </div>

            <div style={{ maxWidth: 760, margin: '0 auto', padding: '32px 24px' }}>
                <div className="kicker" style={{ marginBottom: 8 }}>My Account</div>
                <h2 style={{ margin: '0 0 4px' }}>{profile?.displayName || 'Welcome'}</h2>
                <p className="mono" style={{ opacity: 0.55, fontSize: 13, marginTop: 0 }}>{user.email}</p>

                {!user.emailVerified && (
                    <div className="card" style={{ marginTop: 20, padding: '14px 18px', display: 'flex', gap: 12, alignItems: 'flex-start', background: 'var(--color-accent-100)', borderColor: 'var(--color-accent-300)' }}>
                        <AlertTriangle size={18} color="#B45309" style={{ flex: 'none', marginTop: 2 }} />
                        <div style={{ fontSize: 14 }}>
                            {grace.allowed ? (
                                <>
                                    <b>Verify your email.</b> Everything works for now — about {grace.hoursLeft} hours left.
                                    Verifying is also how you get back in if you forget your password.
                                </>
                            ) : (
                                <><b>Your email still isn't verified.</b> Please click the link we sent before adding devices or playlists.</>
                            )}
                            <div style={{ marginTop: 10 }}>
                                <button onClick={() => nav(withSearch('/account/verify', search))} className="btn btn-secondary" style={{ fontSize: 13 }}>Verify now</button>
                            </div>
                        </div>
                    </div>
                )}

                <div style={{ display: 'grid', gap: 16, marginTop: 24 }}>
                    <button onClick={() => nav('/link')} className="card" style={{ padding: 22, display: 'flex', gap: 14, alignItems: 'flex-start', textAlign: 'left', cursor: 'pointer', width: '100%', font: 'inherit' }}>
                        <MonitorSmartphone size={20} style={{ flex: 'none', marginTop: 2, opacity: 0.5 }} />
                        <div>
                            <h3 style={{ margin: '0 0 4px', fontSize: 16 }}>Add a TV</h3>
                            <p style={{ margin: 0, fontSize: 14, opacity: 0.6 }}>Enter the code shown on your TV screen to link it to this account.</p>
                        </div>
                    </button>
                    <button onClick={() => nav('/account/playlists')} className="card" style={{ padding: 22, display: 'flex', gap: 14, alignItems: 'flex-start', textAlign: 'left', cursor: 'pointer', width: '100%', font: 'inherit' }}>
                        <ListVideo size={20} style={{ flex: 'none', marginTop: 2, opacity: 0.5 }} />
                        <div>
                            <h3 style={{ margin: '0 0 4px', fontSize: 16 }}>Your playlists</h3>
                            <p style={{ margin: 0, fontSize: 14, opacity: 0.6 }}>Add your IPTV details once and every device you own picks them up.</p>
                        </div>
                    </button>
                </div>
            </div>
        </div>
    )
}
