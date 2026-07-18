import { initializeApp } from 'firebase/app';
import { initializeFirestore } from 'firebase/firestore';
import { getAuth } from 'firebase/auth';
import { getFunctions } from 'firebase/functions';

const firebaseConfig = {
    apiKey: "AIzaSyDpBUBq_GowUtJVEsV61lX60804DBt7V4A",
    authDomain: "debridxtream-new.firebaseapp.com",
    projectId: "debridxtream-new",
    storageBucket: "debridxtream-new.firebasestorage.app",
    messagingSenderId: "617090864361",
    appId: "1:617090864361:web:e6876bf871a578d5c54c78",
    measurementId: "G-EJHWMSL738"
};

const app = initializeApp(firebaseConfig);

// Use initializeFirestore instead of getFirestore to enable long polling
// This is a robust fix for "Connection Timeout" issues on mobile networks
export const db = initializeFirestore(app, {
    experimentalForceLongPolling: true,
});

// Reseller dashboard: email/password auth + callable credit-engine functions.
export const auth = getAuth(app);
export const functions = getFunctions(app);

