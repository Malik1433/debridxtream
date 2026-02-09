package com.tvonnet.debridxtreamiptv.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Week 8: Network Connectivity Monitor
 * 
 * Monitors network connectivity changes and provides a Flow of network status.
 * This enables the app to:
 * - Auto-refresh when network becomes available
 * - Show offline indicators
 * - Implement smart retry logic
 * 
 * Uses modern NetworkCallback API (API 21+) with fallback for older APIs.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "NetworkMonitor"
    }
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    /**
     * Flow that emits true when network is available, false otherwise
     * 
     * This Flow is hot - it starts monitoring when collected and stops when cancelled.
     * Use `distinctUntilChanged()` to avoid duplicate emissions.
     */
    val isOnline: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private val networks = mutableSetOf<Network>()
            
            override fun onAvailable(network: Network) {
                networks.add(network)
                Log.d(TAG, "✅ Network available: $network (Total: ${networks.size})")
                trySend(true)
            }
            
            override fun onLost(network: Network) {
                networks.remove(network)
                Log.d(TAG, "❌ Network lost: $network (Remaining: ${networks.size})")
                // Only emit false if no networks left
                if (networks.isEmpty()) {
                    trySend(false)
                }
            }
            
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                
                Log.d(TAG, "🔄 Network capabilities changed: $network | Internet: $hasInternet | Validated: $hasValidated")
                
                if (hasInternet && hasValidated) {
                    trySend(true)
                } else if (networks.isEmpty()) {
                    trySend(false)
                }
            }
        }
        
        // Register network callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(request, callback)
        
        // Send current state immediately
        trySend(isCurrentlyOnline())
        
        // Cleanup when Flow is cancelled
        awaitClose {
            Log.d(TAG, "Unregistering network callback")
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()
    
    /**
     * Check if network is currently available (synchronous)
     * 
     * This is a one-time check, not continuous monitoring.
     * Use `isOnline` Flow for continuous monitoring.
     */
    fun isCurrentlyOnline(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            
            hasInternet && hasValidated
        } else {
            // Fallback for API < 23
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            networkInfo?.isConnected == true
        }
    }
    
    /**
     * Get current network type
     * 
     * Returns one of: WIFI, CELLULAR, ETHERNET, or NONE
     */
    fun getCurrentNetworkType(): NetworkType {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE
            
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                else -> NetworkType.NONE
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return NetworkType.NONE
            
            @Suppress("DEPRECATION")
            return when (networkInfo.type) {
                ConnectivityManager.TYPE_WIFI -> NetworkType.WIFI
                ConnectivityManager.TYPE_MOBILE -> NetworkType.CELLULAR
                ConnectivityManager.TYPE_ETHERNET -> NetworkType.ETHERNET
                else -> NetworkType.NONE
            }
        }
    }
    
    /**
     * Check if connection is metered (e.g., cellular data)
     * 
     * Use this to avoid large downloads on expensive connections.
     */
    fun isConnectionMetered(): Boolean {
        return connectivityManager.isActiveNetworkMetered
    }
}

/**
 * Network type enum
 */
enum class NetworkType {
    WIFI,
    CELLULAR,
    ETHERNET,
    NONE
}

/**
 * Network status data class
 */
data class NetworkStatus(
    val isOnline: Boolean,
    val type: NetworkType,
    val isMetered: Boolean
) {
    val isWifi: Boolean get() = type == NetworkType.WIFI
    val isCellular: Boolean get() = type == NetworkType.CELLULAR
    val isEthernet: Boolean get() = type == NetworkType.ETHERNET
    
    override fun toString(): String {
        return "NetworkStatus(online=$isOnline, type=$type, metered=$isMetered)"
    }
}

