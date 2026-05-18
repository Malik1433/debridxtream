import { db } from '../firebase'
import { doc, getDoc, setDoc, serverTimestamp } from 'firebase/firestore'

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
     * Pushes the configuration to Firestore for the given device code
     */
    async pushConfig(code: string, config: AppConfig): Promise<void> {
        try {
            const docRef = doc(db, 'device_codes', code)
            const addonUrls = config.stremioAddonUrls ?? config.debridConfig?.stremioAddonUrls ?? []
            const payload: Record<string, unknown> = {
                schemaVersion: config.schemaVersion ?? 2,
                debridConfig: {
                    ...(config.debridConfig ?? {}),
                    stremioAddonUrls: addonUrls
                },
                stremioAddonUrls: addonUrls,
                status: 'completed',
                updatedAt: serverTimestamp()
            }

            if (config.iptv) {
                payload.iptv = config.iptv
            }

            console.log(
                'Pushing companion config',
                {
                    code,
                    schemaVersion: payload.schemaVersion,
                    hasIptv: Boolean(config.iptv),
                    addonCount: addonUrls.length
                }
            );

            // Wrap setDoc with a 15s timeout
            await withTimeout(setDoc(docRef, payload, { merge: true }), 15000)
            console.log('Config pushed successfully');
        } catch (error) {
            console.error('Detailed Error pushing config:', error)
            throw error
        }
    }
}

