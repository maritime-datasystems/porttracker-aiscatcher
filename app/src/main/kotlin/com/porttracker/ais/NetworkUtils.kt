package com.porttracker.ais

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Executors

/**
 * Utility class for network information
 */
object NetworkUtils {
    private const val TAG = "porttracker-service.Network"
    private val executor = Executors.newSingleThreadExecutor()
    
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
        executor.execute {
            try {
                val externalIp = URL("https://api.ipify.org").readText().trim()
                callback(externalIp)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching external IP", e)
                callback("Unknown")
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
