package com.porttracker.ais

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Utility class for network information
 */
object NetworkUtils {
    private const val TAG = "porttracker-service.Network"
    private val executor = Executors.newSingleThreadExecutor()
    
    private var cachedExternalIp: String? = null
    private var lastExternalIpFetchTime = 0L
    private const val EXTERNAL_IP_CACHE_TTL = 5 * 60 * 1000L  // 5 minutes
    
    /**
     * Get the device's local IP address on the current network
     */
    fun getLocalIpAddress(context: Context): String {
        try {
            // Try WiFi first
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.ipAddress?.let { ip ->
                if (ip != 0) {
                    return formatIpAddress(ip)
                }
            }
            
            // Fall back to network interfaces
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    networkInterface.inetAddresses.toList().forEach { address ->
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            return address.hostAddress ?: "Unknown"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }
        return "Not connected"
    }
    
    /**
     * Fetch external IP address asynchronously
     */
    fun getExternalIpAddress(callback: (String) -> Unit) {
        val now = System.currentTimeMillis()
        val cached = cachedExternalIp
        if (cached != null && now - lastExternalIpFetchTime < EXTERNAL_IP_CACHE_TTL) {
            callback(cached)
            return
        }
        executor.execute {
            try {
                val conn = URL("https://api.ipify.org").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val externalIp = conn.inputStream.bufferedReader().readText().trim()
                conn.disconnect()
                cachedExternalIp = externalIp
                lastExternalIpFetchTime = System.currentTimeMillis()
                callback(externalIp)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching external IP", e)
                callback(cachedExternalIp ?: "Unknown")  // Return stale cache on error
            }
        }
    }
    
    /**
     * Check if device has active network connection
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    private fun formatIpAddress(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}
