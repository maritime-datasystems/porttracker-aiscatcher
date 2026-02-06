package com.porttracker.ais

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the FRP (Fast Reverse Proxy) tunnel for remote access to the web interface.
 */
class FrpTunnelManager(private val context: Context) {
    
    companion object {
        private const val TAG = "porttracker-service.FRP"
        
        // FRP server configuration
        private const val FRP_SERVER_ADDR = "connect.porttracker.co"
        private const val FRP_SERVER_PORT = 7000
        private const val FRP_AUTH_TOKEN = "porttrackerruleztheworld2026"
    }
    
    private var frpProcess: Process? = null
    private var isRunning = false
    
    /**
     * Get the appropriate binary name for the device architecture.
     */
    private fun getBinaryName(): String {
        val abis = Build.SUPPORTED_ABIS
        return when {
            abis.any { it.contains("arm64") } -> "frpc_arm64"
            abis.any { it.contains("x86_64") } -> "frpc_x86_64"
            abis.any { it.contains("x86") } -> "frpc_x86"
            abis.any { it.contains("armeabi") } -> "frpc_arm32"
            else -> "frpc_arm64" // Fallback
        }
    }
    
    /**
     * Copy the frpc binary from assets to the app's filesDir and make it executable.
     * Returns the File object for the binary, or null on failure.
     */
    private fun copyBinaryFromAssets(): File? {
        val binaryName = getBinaryName()
        val targetFile = File(context.filesDir, "frpc")
        
        try {
            // Check if binary exists in assets
            val assetList = context.assets.list("") ?: emptyArray()
            if (!assetList.contains(binaryName)) {
                Log.e(TAG, "Binary not found in assets: $binaryName")
                return null
            }
            
            // Copy binary
            context.assets.open(binaryName).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Make executable
            targetFile.setExecutable(true, false)
            Log.i(TAG, "Binary copied and made executable: ${targetFile.absolutePath}")
            return targetFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy binary from assets", e)
            return null
        }
    }
    
    /**
     * Generate the config.toml file for frpc.
     */
    private fun generateConfig(stationName: String, webPort: Int): File? {
        val configFile = File(context.filesDir, "frpc.toml")
        
        // Sanitize station name for use as subdomain (alphanumeric and hyphens only)
        val sanitizedName = stationName.lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(32)
        
        val subdomain = if (sanitizedName.isNotEmpty()) sanitizedName else "station-${System.currentTimeMillis()}"
        
        val configContent = """
            serverAddr = "$FRP_SERVER_ADDR"
            serverPort = $FRP_SERVER_PORT
            auth.token = "$FRP_AUTH_TOKEN"
            
            [[proxies]]
            name = "$subdomain"
            type = "http"
            localPort = $webPort
            customDomains = ["$subdomain.connect.porttracker.co"]
        """.trimIndent()
        
        try {
            configFile.writeText(configContent)
            Log.i(TAG, "Config generated: ${configFile.absolutePath}")
            Log.d(TAG, "Config content:\n$configContent")
            return configFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate config", e)
            return null
        }
    }
    
    /**
     * Start the FRP tunnel.
     * @param stationName The station name to use for the subdomain.
     * @param webPort The local web interface port to tunnel.
     * @return true if started successfully, false otherwise.
     */
    fun startTunnel(stationName: String, webPort: Int): Boolean {
        if (isRunning) {
            Log.w(TAG, "Tunnel already running")
            return true
        }
        
        val binary = copyBinaryFromAssets()
        if (binary == null || !binary.exists()) {
            Log.e(TAG, "Failed to prepare frpc binary")
            return false
        }
        
        val config = generateConfig(stationName, webPort)
        if (config == null || !config.exists()) {
            Log.e(TAG, "Failed to generate config")
            return false
        }
        
        try {
            val processBuilder = ProcessBuilder(
                binary.absolutePath,
                "-c", config.absolutePath
            )
            processBuilder.directory(context.filesDir)
            processBuilder.redirectErrorStream(true)
            
            // Start in background thread
            Thread {
                try {
                    frpProcess = processBuilder.start()
                    isRunning = true
                    Log.i(TAG, "Tunnel started successfully")
                    
                    // Log output for debugging
                    frpProcess?.inputStream?.bufferedReader()?.use { reader ->
                        reader.forEachLine { line ->
                            Log.d(TAG, "frpc: $line")
                        }
                    }
                    
                    frpProcess?.waitFor()
                    isRunning = false
                    Log.i(TAG, "Tunnel process exited")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Tunnel process error", e)
                    isRunning = false
                }
            }.start()
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel", e)
            return false
        }
    }
    
    /**
     * Stop the FRP tunnel.
     */
    fun stopTunnel() {
        try {
            frpProcess?.let { process ->
                Log.i(TAG, "Stopping tunnel...")
                process.destroy()
                
                // Force kill if not stopped after 3 seconds
                Thread {
                    try {
                        Thread.sleep(3000)
                        if (process.isAlive) {
                            process.destroyForcibly()
                            Log.w(TAG, "Tunnel force killed")
                        }
                    } catch (e: InterruptedException) {
                        // Ignore
                    }
                }.start()
            }
            frpProcess = null
            isRunning = false
            Log.i(TAG, "Tunnel stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tunnel", e)
        }
    }
    
    /**
     * Check if the tunnel is currently running.
     */
    fun isRunning(): Boolean = isRunning
}
