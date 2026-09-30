import type { LockReason } from '../../license/policy'

const WHY: Record<LockReason, string> = {
  pending: 'This TV is not activated yet.',
  trial_ended: 'Your 7-day trial has ended.',
  expired: 'Your subscription has expired.',
  deactivated: 'This TV has been deactivated.',
  offline_too_long: 'DX Play could not reach the licence server for a while.',
}

/** The locked gate (Android: the activation screen). The code is what the customer reads out. */
export function Activation({ reason, code, registered }: { reason: LockReason; code: string; registered: boolean }) {
  return (
    <div className="center-screen">
      <div className="brand big">DX <span>Play</span></div>
      <h1>{WHY[reason]}</h1>
      {reason === 'offline_too_long' ? (
        <p>Connect this TV to the internet. Your subscription is checked online.</p>
      ) : (
        <>
          <p>Give this code to your provider to activate this TV:</p>
          <div className="code">{code}</div>
          <p className="muted">{registered ? 'The TV opens by itself as soon as it is activated.' : 'Registering this TV…'}</p>
        </>
      )}
    </div>
  )
}
