import { initializeApp, type FirebaseApp } from 'firebase/app'
import { getAuth, type Auth } from 'firebase/auth'
import { initializeFirestore, type Firestore } from 'firebase/firestore'

// The project's public web config (the same one admin-panel and web-dashboard ship). Not a secret:
// what protects the data is firestore.rules.
const CONFIG = {
  apiKey: 'AIzaSyDpBUBq_GowUtJVEsV61lX60804DBt7V4A',
  authDomain: 'debridxtream-new.firebaseapp.com',
  projectId: 'debridxtream-new',
  storageBucket: 'debridxtream-new.firebasestorage.app',
  messagingSenderId: '617090864361',
  appId: '1:617090864361:web:e6876bf871a578d5c54c78',
}

let app: FirebaseApp | null = null
let db: Firestore | null = null

export function firebase(): { db: Firestore; auth: Auth } {
  if (!app) {
    app = initializeApp(CONFIG)
    // TV networks and proxies break WebChannel streaming often enough: let the SDK fall back to
    // long polling on its own (the web dashboard forces it for mobile networks).
    db = initializeFirestore(app, { experimentalAutoDetectLongPolling: true })
  }
  return { db: db!, auth: getAuth(app) }
}
