package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * A process-lifetime [SharedPreferences] that never touches disk (2026-09-05).
 *
 * This exists for exactly one situation: `EncryptedSharedPreferences.create` failing on a device
 * whose AndroidKeyStore path is broken. The stores it backs — [DebridPreferences] and
 * [TorBoxPreferences] — hold **bearer secrets**, and the owner's setup makes that sharper than it
 * looks: addons are added as a config/manifest LINK that the addon site generates from your API
 * key, so the URL itself carries the credential. Falling back to plain prefs would write that in
 * cleartext; crashing would take the app down. This does neither.
 *
 * The important property is that it is **non-destructive**. The encrypted file on disk is left
 * exactly as it was, so a device that recovers its KeyStore comes back with every addon and
 * setting intact — unlike "delete the keyset and start over", which would silently throw the
 * user's configuration away. The cost while degraded is honest and small: the session behaves as
 * if nothing were configured, and changes do not survive a restart.
 */
class InMemoryPrefs : SharedPreferences {

    private val values = ConcurrentHashMap<String, Any>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    // Each accessor casts to its OWN concrete type. A generic `(values[key] as? T)` helper looks
    // tidier and is broken: T is erased, so the cast always succeeds and getInt() happily returned
    // a String. Caught by InMemoryPrefsTest before this shipped.
    //
    // A wrong-type read returns the DEFAULT here, where the platform implementation throws
    // ClassCastException. That is a deliberate difference: this class only exists on the path
    // where things have already gone wrong, and its job is to not bring the app down.
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.add(l) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun notifyChanged(keys: Collection<String>) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        keys.forEach { key -> snapshot.forEach { it.onSharedPreferenceChanged(this@InMemoryPrefs, key) } }
    }

    /** Buffers like the real one: nothing is visible until apply()/commit(). */
    private inner class Editor : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, Any>()
        private val removes = LinkedHashSet<String>()
        private var clearAll = false

        private fun put(key: String, value: Any): SharedPreferences.Editor {
            puts[key] = value
            removes.remove(key)
            return this
        }

        override fun putString(key: String, value: String?) = if (value == null) remove(key) else put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            if (values == null) remove(key) else put(key, LinkedHashSet(values))
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            removes.add(key)
            puts.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            val touched = LinkedHashSet<String>()
            if (clearAll) {
                touched.addAll(values.keys)
                values.clear()
            }
            removes.forEach { values.remove(it); touched.add(it) }
            puts.forEach { (k, v) -> values[k] = v; touched.add(k) }
            notifyChanged(touched)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
