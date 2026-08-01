import { db } from '../firebase'
import { doc, getDoc } from 'firebase/firestore'

export interface AppConfig {
    schemaVersion?: number
    iptv?: {
        url: string
        username: string
        password: string
    }
    debridConfig?: {
        token?: string
        mediaFusionUrl?: string
        stremioAddonUrls?: string[]
        addonRegistryUrls?: string[]
    }
    stremioAddonUrls?: string[]
    debrid?: string
    mediafusion?: string
}

// Helper to wrap Firestore calls with a timeout
const withTimeout = <T>(promise: Promise<T>, timeoutMs: number = 10000): Promise<T> => {
    return Promise.race([
        promise,
        new Promise<T>((_, reject) =>
            setTimeout(() => reject(new Error('Connection timed out. Please check your internet.')), timeoutMs)
        )
    ]);
};

export const FirestoreService = {
    /**
     * Verifies if a device code exists and is waiting for input
     */
    async verifyCode(code: string): Promise<boolean> {
        try {
            const docRef = doc(db, 'device_codes', code)
            // Wrap getDoc with a 10s timeout
            const docSnap = await withTimeout(getDoc(docRef), 10000)
            return docSnap.exists()
        } catch (error) {
            console.error('Error verifying code:', error)
            throw error // Throwing so the UI can catch the message
        }
    },

    /**
     * Loads the last-pushed configuration for a device key (used to prefill the
     * form, and to copy the same settings to another device).
     */
    async loadConfig(code: string): Promise<Record<string, unknown> | null> {
        try {
            const docRef = doc(db, 'device_codes', code)
            const snap = await withTimeout(getDoc(docRef), 10000)
            return snap.exists() ? (snap.data() as Record<string, unknown>) : null
        } catch (error) {
            console.error('Error loading config:', error)
            return null
        }
    },

    /**
     * Pushes the configuration to Firestore for the given device code
     */
    /**
     * REMOVED (§7 U8b, 2026-08-01). This wrote the customer's Xtream username and password into
     * `device_codes/{code}` — a document readable by anyone who learned the device key, which is
     * printed on the TV screen. Configuration now lives in `playlists`, owner-scoped, and the
     * Firestore rules refuse credential fields on `device_codes` outright, so this cannot be
     * reinstated by accident.
     */
}

