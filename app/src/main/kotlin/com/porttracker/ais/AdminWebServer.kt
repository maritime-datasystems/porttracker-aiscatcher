package com.porttracker.ais

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

class AdminWebServer(
    private val service: AisReceiverService,
    private val port: Int,
    private val internalApiPort: Int
) : NanoHTTPD(null, port) {

    private val TAG = "porttracker-service.AdminWeb"
    private var isRunning = false
    private val configManager = ConfigurationManager(service)

    override fun start() {
        try {
            super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            isRunning = true
            Log.i(TAG, "Admin Web Server started on port $port")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start Admin Web Server", e)
        }
    }

    override fun stop() {
        super.stop()
        isRunning = false
        Log.i(TAG, "Admin Web Server stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        // Check authentication if enabled
        val authResponse = checkAuthentication(session)
        if (authResponse != null) {
            return authResponse
        }

        // 1. Admin API Endpoints
        if (uri.startsWith("/admin/")) {
            return handleAdminApi(session)
        }

        // 2. Reverse Proxy for Internal C++ API
        // Forward stats, metrics, tile requests, and SSE to the internal server
        if (shouldProxy(uri)) {
            return proxyToInternalServer(session)
        }

        // 3. Static File Serving (Custom Web Interface)
        // Check filesDir/web first (for overrides)
        val webDir = File(service.filesDir, "web")
        val relativePath = if (uri == "/") "index.html" else uri.substring(1)
        val requestedFile = File(webDir, relativePath)

        if (requestedFile.exists() && requestedFile.isFile) {
            return serveStaticFile(requestedFile)
        }

        // 4. Fallback: Check APK assets/web
        val assetPath = "web/$relativePath"
        try {
            val assetStream = service.assets.open(assetPath)
            val mimeType = determineMimeType(relativePath)
            Log.d(TAG, "Serving from APK assets: $assetPath")
            return newChunkedResponse(Response.Status.OK, mimeType, assetStream)
        } catch (e: IOException) {
            // Asset not found, continue to proxy
        }

        // 5. Last Resort: Proxy to internal server (e.g. native API endpoints not caught above)
        return proxyToInternalServer(session)
    }

    private fun shouldProxy(uri: String): Boolean {
        return uri.startsWith("/api/") || 
               uri.startsWith("/metrics") || 
               uri == "/stat.json" ||
               uri.startsWith("/tiles/") ||
               uri.startsWith("/cdn/")
    }

    private fun handleAdminApi(session: IHTTPSession): Response {
        if (session.uri == "/admin/status") {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"running\",\"service\":\"active\"}")
        }
        
        if (session.uri == "/admin/api/config") {
            if (session.method == Method.GET) {
                val json = configManager.getAllConfig()
                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            } else if (session.method == Method.POST) {
                try {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val jsonString = map["postData"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing body")
                    
                    if (configManager.updateConfig(jsonString)) {
                        // Auto-restart logic
                        service.triggerEngineRestart()
                        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"updated\",\"restart_triggered\":true}")
                    } else {
                         return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"status\":\"error\",\"message\":\"Failed to update preferences\"}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing POST data", e)
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error processing request")
                }
            }
        }
        
        if (session.uri == "/admin/api/status_ui") {
            return newFixedLengthResponse(Response.Status.OK, "application/json", getUiStatusJson())
        }
        
        // SDR Status endpoint for settings page
        if (session.uri == "/admin/api/status") {
            return newFixedLengthResponse(Response.Status.OK, "application/json", getSdrStatusJson())
        }
        
        // Service control endpoints
        if (session.method == Method.POST && session.uri == "/admin/api/service/start") {
            try {
                val intent = android.content.Intent(service, AisReceiverService::class.java)
                androidx.core.content.ContextCompat.startForegroundService(service, intent)
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"success":false,"error":"${e.message}"}""")
            }
        }
        
        if (session.method == Method.POST && session.uri == "/admin/api/service/stop") {
            try {
                service.stopSelf()
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"success":false,"error":"${e.message}"}""")
            }
        }

        if (session.method == Method.POST && session.uri == "/admin/restart") {
             service.triggerEngineRestart()
             return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"restarting_soon\"}")
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Admin Endpoint Not Found")
    }
    
    private fun getSdrStatusJson(): String {
        val ctx = service.applicationContext
        val deviceInfo = UsbDeviceScanner.scanForDevices(ctx)
        val usbManager = ctx.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager

        // Use JSONArray/JSONObject so device names with quotes or backslashes can't break the output.
        val usbDevicesArray = org.json.JSONArray()
        for (device in usbManager.deviceList.values) {
            val obj = org.json.JSONObject()
            obj.put("name", device.productName ?: device.deviceName)
            obj.put("vendorId", device.vendorId)
            obj.put("productId", device.productId)
            obj.put("isSDR", UsbDeviceScanner.isSupportedDevice(device.vendorId, device.productId))
            obj.put("hasPermission", usbManager.hasPermission(device))
            usbDevicesArray.put(obj)
        }

        val root = org.json.JSONObject()
        root.put("service_running", AisReceiverService.isRunning)
        root.put("sdr_connected", AisReceiverService.hasDevice)
        root.put("sdr_permission", deviceInfo.found && deviceInfo.isUsable)
        root.put("sdr_device_name", if (deviceInfo.found) deviceInfo.deviceName else "No device")
        root.put("message_count", AisReceiverService.messageCount)
        root.put("usb_devices", usbDevicesArray)
        return root.toString()
    }

    private fun getUiStatusJson(): String {
        val ctx = service.applicationContext
        
        // Network
        val localIp = NetworkUtils.getLocalIpAddress(ctx)
        val isNetAvailable = NetworkUtils.isNetworkAvailable(ctx)
        val netStatus = if (isNetAvailable) "Connected" else "Disconnected"
        
        // Service
        val serviceRunning = AisReceiverService.isRunning
        val serviceStatus = if (serviceRunning) "🟢 Running" else "🔴 Stopped"
        
        // Device
        val deviceInfo = UsbDeviceScanner.scanForDevices(ctx)
        val deviceSummary = when {
            AisReceiverService.hasDevice -> "✅ ${deviceInfo.deviceName} - Active"
            deviceInfo.found && deviceInfo.isUsable -> "🔌 ${deviceInfo.deviceName} - Ready"
            deviceInfo.found && !deviceInfo.isUsable -> "⚠️ ${deviceInfo.deviceName} - No Permission"
            else -> "❌ No SDR device"
        }
        
        // GPS
        val gpsEnabled = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("gpsd_enabled", false)
        val gpsStatus = if (!gpsEnabled) "❌ Disabled" else if (GpsForwarder.isForwarding) "🟢 Forwarding" else "⏸️ Waiting"
        
        val gpsCoords = if (GpsForwarder.hasPosition) {
             val base = String.format(java.util.Locale.US, "%.6f, %.6f", GpsForwarder.lastLatitude, GpsForwarder.lastLongitude)
             if (GpsForwarder.hasHeading) "$base 🧭 ${String.format("%.0f", GpsForwarder.lastHeading)}°" else base
        } else "📍 Acquiring..."
        
        // Version
        val appVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) { "Unknown" }

        // JSON Building
        return """
            {
                "app_version": "$appVersion",
                "local_ip": "$localIp",
                "network_status": "$netStatus",
                "service_status": "$serviceStatus",
                "device_status": "$deviceSummary",
                "msg_count": ${AisReceiverService.messageCount},
                "gps_status": "$gpsStatus",
                "gps_coordinates": "$gpsCoords",
                "gps_msg_count": ${GpsForwarder.messagesLastMinute}
            }
        """.trimIndent()
    }

    private fun serveStaticFile(file: File): Response {
        return try {
            val mimeType = determineMimeType(file.name)
            val fis = FileInputStream(file)
            newFixedLengthResponse(Response.Status.OK, mimeType, fis, file.length())
        } catch (e: IOException) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error serving file")
        }
    }

    private fun determineMimeType(name: String): String {
        return when {
            name.endsWith(".html") -> "text/html"
            name.endsWith(".js") -> "application/javascript"
            name.endsWith(".css") -> "text/css"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") -> "image/jpeg"
            name.endsWith(".ico") -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }

    private fun proxyToInternalServer(session: IHTTPSession): Response {
        val internalUrl = "http://127.0.0.1:$internalApiPort${session.uri}${if (session.queryParameterString != null) "?" + session.queryParameterString else ""}"
        
        return try {
            val url = URL(internalUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = session.method.name
            
            // Disable read timeout for streaming endpoints (SSE)
            if (session.uri.startsWith("/api/sse") || session.uri.startsWith("/api/log")) {
                connection.readTimeout = 0 // Infinite
                Log.d(TAG, "Proxying SSE stream: infinite timeout enabled")
            } else {
                connection.readTimeout = 10000
            }
            
        connection.connectTimeout = 5000
        connection.setRequestProperty("Accept-Encoding", "identity") // Force uncompressed
        
        val responseCode = connection.responseCode
        val rawStream = if (responseCode < 400) connection.inputStream else connection.errorStream
        val contentType = connection.contentType ?: "application/octet-stream"
        
        Log.d(TAG, "Proxying $internalUrl: Code=$responseCode, Type=$contentType")
        
        val finalStreamToUse = if (contentType.startsWith("text/event-stream")) {
            Log.d(TAG, "Starting SSE Heartbeat Proxy")
            SseStreamHandler(rawStream).getInputStream()
        } else {
            rawStream
        }

        val response = newFixedLengthResponse(
            Response.Status.lookup(responseCode),
            contentType,
            finalStreamToUse,
            -1 // Chunked
        )
        
        // Forward key headers
        val cacheControl = connection.getHeaderField("Cache-Control")
        if (cacheControl != null) response.addHeader("Cache-Control", cacheControl)
        
        // Add headers for SSE stability
        if (contentType.startsWith("text/event-stream")) {
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("X-Accel-Buffering", "no")
            response.addHeader("Connection", "keep-alive")
        }
        
        return response

        } catch (e: Exception) {
            Log.e(TAG, "Proxy Error to $internalUrl", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: ${e.message}")
        }
    }
    
    // Helper class to inject heartbeats into SSE stream
    private class SseStreamHandler(private val upstream: InputStream) {
        private val pipedOut = java.io.PipedOutputStream()
        private val pipedIn = java.io.PipedInputStream(pipedOut, 4096)
        private val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        private var isRunning = true
        
        fun getInputStream(): InputStream {
            startRelay()
            startHeartbeat()
            return pipedIn
        }
        
        private fun startRelay() {
            executor.submit {
                try {
                    val buffer = ByteArray(4096)
                    var read: Int = 0
                    while (isRunning && upstream.read(buffer).also { read = it } != -1) {
                        synchronized(pipedOut) {
                            pipedOut.write(buffer, 0, read)
                            pipedOut.flush()
                        }
                    }
                } catch (e: Exception) {
                    // Log.d("SseProxy", "Relay finished/error: ${e.message}")
                } finally {
                    close()
                }
            }
        }
        
        private fun startHeartbeat() {
            executor.submit {
                try {
                    // 4KB padding to force flush (proven necessary for Android/WebView)
                    val padding = " ".repeat(4096)
                    val heartbeat = ": keep-alive $padding\n\n".toByteArray()
                    while (isRunning) {
                        Thread.sleep(2000)
                        synchronized(pipedOut) {
                            pipedOut.write(heartbeat)
                            pipedOut.flush()
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        
        private fun close() {
            isRunning = false
            try { pipedOut.close() } catch (e: Exception) {}
            try { upstream.close() } catch (e: Exception) {}
            executor.shutdownNow()
        }
    }

    /**
     * Check HTTP Basic Authentication if enabled in settings.
     * Returns null if auth passes or is disabled, returns 401 response if auth fails.
     */
    private fun checkAuthentication(session: IHTTPSession): Response? {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(service)
        
        // Check if authentication is enabled
        val authEnabled = prefs.getBoolean("web_auth_enabled", false)
        if (!authEnabled) {
            return null // Auth disabled, allow access
        }
        
        val requiredUsername = prefs.getString("web_auth_username", "") ?: ""
        val requiredPassword = prefs.getString("web_auth_password", "") ?: ""
        
        // If no credentials configured, skip auth
        if (requiredUsername.isEmpty() || requiredPassword.isEmpty()) {
            return null
        }
        
        // Get Authorization header
        val authHeader = session.headers["authorization"]
        
        if (authHeader == null || !authHeader.startsWith("Basic ", ignoreCase = true)) {
            return createUnauthorizedResponse()
        }
        
        try {
            // Decode Base64 credentials
            val base64Credentials = authHeader.substring(6)
            val credentials = String(Base64.decode(base64Credentials, Base64.DEFAULT))
            val colonIndex = credentials.indexOf(':')
            
            if (colonIndex == -1) {
                return createUnauthorizedResponse()
            }
            
            val username = credentials.substring(0, colonIndex)
            val password = credentials.substring(colonIndex + 1)
            
            // Validate credentials
            if (username == requiredUsername && password == requiredPassword) {
                return null // Auth passed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding auth header", e)
        }
        
        return createUnauthorizedResponse()
    }
    
    private fun createUnauthorizedResponse(): Response {
        val response = newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            "text/html",
            """
            <!DOCTYPE html>
            <html>
            <head><title>401 Unauthorized</title></head>
            <body>
                <h1>401 Unauthorized</h1>
                <p>Authentication required to access this resource.</p>
            </body>
            </html>
            """.trimIndent()
        )
        response.addHeader("WWW-Authenticate", "Basic realm=\"PortTracker AIS\"")
        return response
    }
}
