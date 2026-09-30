import { Qr } from '../Qr'

export const LINK_URL = 'https://dxplay.xyz/link'

/**
 * No playlist yet. Typing a server, username and password with a TV remote is the worst part of
 * every IPTV app, so the phone does it: the QR opens the account page with this TV's code, the
 * customer adds the TV and a playlist there, and it arrives here by itself (AccountSync).
 */
export function Setup({ code, claimed }: { code: string; claimed: boolean }) {
  return (
    <div className="center-screen setup">
      <Qr text={`${LINK_URL}?code=${code}`} size={360} />
      <div>
        <h1>{claimed ? 'TV added to your account' : 'Set up with your phone'}</h1>
        <p>{claimed
          ? 'Now add a playlist on your phone. It appears on this TV by itself.'
          : 'Scan the code with your phone camera, sign in, and add this TV. No typing on the TV.'}</p>
        <p className="muted">TV code</p>
        <div className="code">{code}</div>
      </div>
    </div>
  )
}
