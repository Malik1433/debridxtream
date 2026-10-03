import { setFocus } from '@noriginmedia/norigin-spatial-navigation'
import { useEffect } from 'react'
import { pushBackHandler } from '../../backStack'
import { Focusable } from '../../Focusable'
import { Qr } from '../../Qr'
import { LINK_URL } from '../Setup'

/** Android SettingsManageQrDialog: one code that opens this TV on the phone. BACK or OK closes it. */
export function ManageOnPhone({ code, onClose }: { code: string; onClose: () => void }) {
  useEffect(() => pushBackHandler(() => { onClose(); return true }), [onClose])
  useEffect(() => { void setFocus('qr-close') }, [])
  return (
    <div className="set2-modal">
      <div className="set2-modal-card">
        <h2>Manage on your phone</h2>
        <Qr text={`${LINK_URL}?code=${code}`} size={320} />
        <p>Scan this with your phone to see what this device is using, and change it. You will need to be signed in to your account.</p>
        <Focusable focusKey="qr-close" className="button" onEnter={onClose}>Close</Focusable>
      </div>
    </div>
  )
}
