import { Check, CreditCard, Users } from 'lucide-react'

export const inputCls =
    'w-full rounded-lg border border-white/10 bg-white/5 px-3.5 py-2.5 text-sm text-white placeholder:text-neutral-500 outline-none focus:border-gold-500/60'
export const btnCls =
    'w-full rounded-lg bg-gold-500 px-4 py-2.5 text-sm font-semibold text-black transition hover:bg-gold-400 disabled:opacity-50'

export function Field({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <label className="block">
            <span className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-neutral-400">{label}</span>
            {children}
        </label>
    )
}

/** Two-column auth layout: brand/benefits panel + the form card. Single column on mobile. */
export function AuthShell({ title, subtitle, children }: { title: string; subtitle: string; children: React.ReactNode }) {
    return (
        <div className="grid min-h-screen lg:grid-cols-2">
            {/* brand panel */}
            <div className="relative hidden overflow-hidden bg-gradient-to-br from-neutral-900 to-black lg:flex lg:flex-col lg:justify-between lg:p-12">
                <div className="flex items-center gap-3">
                    <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-gold-500 font-black text-black">DX</div>
                    <span className="text-lg font-bold">DebridXtream · Reseller</span>
                </div>
                <div>
                    <h2 className="max-w-sm text-3xl font-bold leading-tight">Sell and manage your clients, your way.</h2>
                    <ul className="mt-8 space-y-4 text-sm text-neutral-300">
                        <Benefit icon={<CreditCard size={16} />} text="Buy credits and activate devices instantly" />
                        <Benefit icon={<Users size={16} />} text="One dashboard for every client you manage" />
                        <Benefit icon={<Check size={16} />} text="Renew, track expiry, and stay in control" />
                    </ul>
                </div>
                <p className="text-xs text-neutral-500">© DebridXtream — reseller portal</p>
                <div className="pointer-events-none absolute -right-24 -top-24 h-72 w-72 rounded-full bg-gold-500/10 blur-3xl" />
            </div>

            {/* form side */}
            <div className="flex flex-col justify-center px-5 py-10 sm:px-10">
                <div className="mx-auto w-full max-w-md">
                    <div className="mb-8">
                        <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-xl bg-gold-500 font-black text-black lg:hidden">DX</div>
                        <h1 className="text-2xl font-bold">{title}</h1>
                        <p className="mt-1 text-sm text-neutral-400">{subtitle}</p>
                    </div>
                    {children}
                </div>
            </div>
        </div>
    )
}

function Benefit({ icon, text }: { icon: React.ReactNode; text: string }) {
    return (
        <li className="flex items-center gap-3">
            <span className="flex h-7 w-7 items-center justify-center rounded-full bg-gold-500/15 text-gold-300">{icon}</span>
            {text}
        </li>
    )
}

export function errText(err: unknown): string {
    const code = (err as { code?: string })?.code || ''
    if (code.includes('email-already-in-use')) return 'That email is already registered.'
    if (code.includes('invalid-email')) return 'Enter a valid email address.'
    if (code.includes('weak-password')) return 'Password is too weak (min 6 characters).'
    if (code.includes('wrong-password') || code.includes('invalid-credential')) return 'Wrong email or password.'
    if (code.includes('user-not-found')) return 'No account with that email.'
    if (code.includes('too-many-requests')) return 'Too many attempts — try again later.'
    if (code.includes('network-request-failed')) return 'Network error — check your connection.'
    return (err as { message?: string })?.message || 'Something went wrong.'
}

// A compact country list (extend as needed).
export const COUNTRIES = [
    'United States', 'United Kingdom', 'Canada', 'Germany', 'France', 'Netherlands',
    'Spain', 'Italy', 'Pakistan', 'India', 'United Arab Emirates', 'Saudi Arabia',
    'Australia', 'Brazil', 'Mexico', 'Türkiye', 'Other',
]
