/**
 * How long a brand-new, still-unverified account may keep working.
 *
 * §7 D5: email verification must not stand between a paying customer and their first frame. The
 * failure this prevents is specific — someone sets up a TV in the evening, cannot reach their email,
 * and the thing they just paid for does nothing. A grace window costs us almost nothing (an abuser
 * would simply verify a throwaway address) and it is the difference between "works" and "works
 * tomorrow" for a real customer.
 *
 * It is a WINDOW, not an exemption: "never verified" is also what a junk signup looks like, so it
 * ends.
 */
export const VERIFICATION_GRACE_MS = 48 * 60 * 60 * 1000

export interface GraceState {
    /** May this account still claim devices and edit playlists? */
    allowed: boolean
    /** Whole hours left in the window; 0 once it has closed or when verification is done. */
    hoursLeft: number
}

/**
 * @param createdAtMs when the account was created — 0/undefined if unknown.
 * @returns whether the account may act, and how long the banner should say it has.
 *
 * An unknown creation time is treated as "just created" rather than "ancient", for the same reason
 * the licence gate does: being wrong in the strict direction locks out a real customer, while being
 * wrong in the lenient direction costs one grace window.
 */
export function verificationGrace(
    nowMs: number,
    emailVerified: boolean,
    createdAtMs: number | undefined,
    graceMs: number = VERIFICATION_GRACE_MS,
): GraceState {
    if (emailVerified) return { allowed: true, hoursLeft: 0 }
    if (!createdAtMs || createdAtMs > nowMs) return { allowed: true, hoursLeft: Math.ceil(graceMs / 3_600_000) }

    const remaining = createdAtMs + graceMs - nowMs
    if (remaining <= 0) return { allowed: false, hoursLeft: 0 }
    return { allowed: true, hoursLeft: Math.ceil(remaining / 3_600_000) }
}
