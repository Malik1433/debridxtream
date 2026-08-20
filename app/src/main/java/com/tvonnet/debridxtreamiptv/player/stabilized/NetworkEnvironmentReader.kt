package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.getSystemService

/**
 * What the device can say about its own connection, for [StreamHealthMonitor].
 *
 * Read through [NetworkCapabilities] rather than `WifiManager.getConnectionInfo`, which on modern
 * Android returns a redacted result without location permission — and asking a customer for
 * location access to explain their buffering would be a worse trade than not explaining it.
 *
 * `signalStrength` only exists from API 29. Below that the strength comes back null, and the
 * diagnosis simply says "your connection is slow" instead of naming the Wi-Fi. Degrading to a
 * vaguer true statement is right; guessing at the signal is not.
 */
internal object NetworkEnvironmentReader {

    fun read(context: Context, isReconnecting: Boolean): StreamHealthMonitor.Environment {
        val caps = capabilities(context)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return StreamHealthMonitor.Environment(
            isWifi = isWifi,
            wifiRssiDbm = if (isWifi) signalStrength(caps) else null,
            isReconnecting = isReconnecting,
        )
    }

    private fun capabilities(context: Context): NetworkCapabilities? = runCatching {
        val cm = context.applicationContext.getSystemService<ConnectivityManager>()
        cm?.getNetworkCapabilities(cm.activeNetwork)
    }.getOrNull()

    private fun signalStrength(caps: NetworkCapabilities?): Int? {
        if (caps == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val dbm = caps.signalStrength
        // The platform's "unavailable" sentinel; a real Wi-Fi reading is a negative dBm.
        return if (dbm == Int.MIN_VALUE || dbm >= 0) null else dbm
    }
}
