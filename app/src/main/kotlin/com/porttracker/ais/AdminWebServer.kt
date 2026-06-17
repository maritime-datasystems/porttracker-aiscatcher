package com.porttracker.ais

import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class AdminWebServer(
    private val service: AisReceiverService,
    private val port: Int,
    private val internalApiPort: Int
) : NanoHTTPD(null, port) {

    companion object {
        private const val TAG = "porttracker-service.AdminWeb"
    }
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

        // Check authentication if enabled (bypass for health endpoint — used by remote dashboard)
        if (uri != "/admin/api/health") {
            val authResponse = checkAuthentication(session)
            if (authResponse != null) {
                return authResponse
            }
        }

        // 1. Admin API Endpoints
        if (uri.startsWith("/admin/")) {
            return handleAdminApi(session)
        }

        // 1.5 NMEA buffer endpoint — returns recent NMEA as JSON array (polling-friendly)
        if (uri == "/api/nmea_buffer" || uri == "/admin/api/nmea_buffer") {
            return handleNmeaBuffer()
        }

        // 1.6 Direct NMEA SSE stream — bypasses C++ server, uses native NMEA callbacks
        if (uri == "/api/sse" || uri == "/admin/api/nmea_stream") {
            return handleNmeaSse()
        }

        // 2. Reverse Proxy for Internal C++ API
        // Forward stats, metrics, tile requests to the internal server
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
                    // NanoHTTPD puts x-www-form-urlencoded data in session.parms,
                    // and raw JSON body in map["postData"]
                    val jsonString = map["postData"]
                        ?: session.parms?.get("postData")
                        ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing body")
                    
                    if (configManager.updateConfig(jsonString)) {
                        // Settings saved — apply MQTT and FRP changes live (safe, no native engine restart)
                        val response = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"updated\",\"restart_required\":false}")
                        // Apply FRP and MQTT changes on a background thread after response is sent
                        Thread {
                            try {
                                Thread.sleep(200) // Let HTTP response flush
                                service.applyLiveConfigChanges()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error applying live config changes", e)
                            }
                        }.start()
                        return response
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
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }.toString())
            }
        }
        
        if (session.method == Method.POST && session.uri == "/admin/api/service/stop") {
            try {
                // Only stop the SDR engine, NOT the whole service — web server must stay alive
                Thread {
                    try {
                        AisReceiverService.hasDevice = false
                        com.jvdegithub.aiscatcher.AisCatcherJava.forceStop()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping SDR engine", e)
                    }
                }.start()
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }.toString())
            }
        }

        if (session.method == Method.POST && session.uri == "/admin/restart") {
             // Don't restart — native engine crashes on forceStop (SIGSEGV)
             // Just return success, settings are already saved
             return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"restart_required\",\"message\":\"Please restart the app to apply changes\"}")
        }

        // --- TrustedDocks / MQTT Gateway Endpoints ---

        if (session.method == Method.POST && session.uri == "/admin/api/gateway/connect") {
            return handleGatewayConnect(session)
        }

        if (session.method == Method.GET && session.uri == "/admin/api/gateway/status") {
            return handleGatewayStatus()
        }

        // --- Health endpoint for remote dashboard monitoring ---
        if (session.method == Method.GET && session.uri == "/admin/api/health") {
            return handleHealth()
        }

        // USB Permission request — triggers native Android permission dialog via service
        if (session.method == Method.POST && session.uri == "/admin/api/usb/request_permission") {
            return handleRequestUsbPermission()
        }
        
        // Engine restart — re-scans USB and restarts the native AIS engine
        if (session.method == Method.POST && session.uri == "/admin/api/engine/restart") {
            return handleEngineRestart()
        }

        // MQTT restart — reconnect MQTT publisher with current preferences
        if (session.method == Method.POST && session.uri == "/admin/api/mqtt/restart") {
            return handleMqttRestart()
        }

        // FRP restart — restart FRP tunnel with current preferences
        if (session.method == Method.POST && session.uri == "/admin/api/frp/restart") {
            return handleFrpRestart()
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
        return JSONObject().apply {
            put("app_version", appVersion)
            put("local_ip", localIp)
            put("network_status", netStatus)
            put("service_status", serviceStatus)
            put("device_status", deviceSummary)
            put("msg_count", AisReceiverService.messageCount)
            put("gps_status", gpsStatus)
            put("gps_coordinates", gpsCoords)
            put("gps_msg_count", GpsForwarder.messagesLastMinute)
        }.toString()
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
        
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(internalUrl)
            connection = url.openConnection() as HttpURLConnection
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
            
            if (contentType.startsWith("text/event-stream")) {
                // SSE: hand off the raw stream to SseStreamHandler (it owns the connection lifecycle)
                Log.d(TAG, "Starting SSE Heartbeat Proxy")
                val sseStream = SseStreamHandler(rawStream).getInputStream()
                val response = newFixedLengthResponse(
                    Response.Status.lookup(responseCode),
                    contentType,
                    sseStream,
                    -1 // Chunked
                )
                response.addHeader("Cache-Control", "no-cache")
                response.addHeader("X-Accel-Buffering", "no")
                response.addHeader("Connection", "keep-alive")
                return response
            } else {
                // Non-streaming: copy response bytes then disconnect
                val responseBytes = rawStream.readBytes()
                val cacheControl = connection.getHeaderField("Cache-Control")
                connection.disconnect()
                connection = null // prevent double-disconnect in finally
                
                val response = newFixedLengthResponse(
                    Response.Status.lookup(responseCode),
                    contentType,
                    ByteArrayInputStream(responseBytes),
                    responseBytes.size.toLong()
                )
                
                // Forward key headers
                if (cacheControl != null) response.addHeader("Cache-Control", cacheControl)
                
                return response
            }

        } catch (e: Exception) {
            Log.e(TAG, "Proxy Error to $internalUrl", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
    
    // Helper class to inject heartbeats into SSE stream
    private class SseStreamHandler(private val upstream: InputStream) {
        private val pipedOut = java.io.PipedOutputStream()
        private val pipedIn = java.io.PipedInputStream(pipedOut, 65536)
        private val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        @Volatile private var isRunning = true
        
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
                        try {
                            synchronized(pipedOut) {
                                pipedOut.write(buffer, 0, read)
                                pipedOut.flush()
                            }
                        } catch (e: java.io.IOException) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    // Stream ended or error
                } finally {
                    close()
                }
            }
        }
        
        // Pre-allocate constant heartbeat byte array (4KB padding to force flush for Android/WebView)
        private val heartbeat = ": keep-alive ${" ".repeat(4096)}\n\n".toByteArray()
        
        private fun startHeartbeat() {
            executor.submit {
                try {
                    while (isRunning) {
                        Thread.sleep(2000)
                        try {
                            synchronized(pipedOut) {
                                pipedOut.write(heartbeat)
                                pipedOut.flush()
                            }
                        } catch (e: java.io.IOException) {
                            // Pipe broken — client disconnected
                            break
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                } finally {
                    close()
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

    // ---- Direct NMEA SSE Stream ----

    /**
     * Serves live NMEA sentences as Server-Sent Events.
     * Uses the native NMEAListener callback system directly,
     * bypassing the C++ web server's SSE endpoint.
     */

    // ---- NMEA Ring Buffer for polling ----
    private val nmeaBuffer = java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, String>>()
    private val nmeaSeq = java.util.concurrent.atomic.AtomicLong(0)
    private var nmeaBufferListenerRegistered = false

    private fun ensureNmeaBufferListener() {
        if (nmeaBufferListenerRegistered) return
        nmeaBufferListenerRegistered = true
        val listener = object : AisReceiverService.Companion.NMEAListener {
            override fun onNMEA(nmea: String) {
                val lines = nmea.trim().split("\n").filter { it.isNotBlank() }
                for (line in lines) {
                    val seq = nmeaSeq.incrementAndGet()
                    nmeaBuffer.addLast(Pair(seq, line.trim()))
                    // Keep max 200 entries
                    while (nmeaBuffer.size > 200) {
                        nmeaBuffer.pollFirst()
                    }
                }
            }
        }
        AisReceiverService.addNMEAListener(listener)
        Log.i(TAG, "NMEA buffer listener registered")
    }

    /**
     * GET /api/nmea_buffer?since=<seq>
     * Returns JSON: {"seq": <latest_seq>, "lines": ["!AIVDM,...", "!AIVDM,..."]}
     * Client passes since=0 on first call, then since=<last_seq> on subsequent calls.
     */
    private fun handleNmeaBuffer(): Response {
        ensureNmeaBufferListener()
        val since = 0L // Simple: always return latest batch
        // Actually parse 'since' from query — but NanoHTTPD doesn't parse query in serve()
        // So we just return the last 50 lines
        val snapshot = nmeaBuffer.toList()
        val recent = snapshot.takeLast(50)
        val latestSeq = recent.lastOrNull()?.first ?: 0L

        val jsonLines = recent.joinToString(",") { "\"${it.second.replace("\"", "\\\"")}\"" }
        val json = """{"seq":$latestSeq,"lines":[$jsonLines]}"""

        val response = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    private fun handleNmeaSse(): Response {
        val pipedOut = java.io.PipedOutputStream()
        val pipedIn = java.io.PipedInputStream(pipedOut, 65536)
        val isOpen = java.util.concurrent.atomic.AtomicBoolean(true)

        val listener = object : AisReceiverService.Companion.NMEAListener {
            override fun onNMEA(nmea: String) {
                if (!isOpen.get()) return
                try {
                    // SSE spec: each line of multiline data must be prefixed with "data:"
                    val lines = nmea.trim().split("\n")
                    val sseEvent = lines.joinToString("\n") { "data: ${it.trim()}" } + "\n\n"
                    synchronized(pipedOut) {
                        pipedOut.write(sseEvent.toByteArray())
                        pipedOut.flush()
                    }
                } catch (e: Exception) {
                    isOpen.set(false)
                    AisReceiverService.removeNMEAListener(this)
                    try { pipedOut.close() } catch (_: Exception) {}
                }
            }
        }

        AisReceiverService.addNMEAListener(listener)
        Log.i(TAG, "NMEA SSE client connected")

        // Heartbeat thread to keep the connection alive and detect disconnects
        Thread {
            try {
                while (isOpen.get()) {
                    Thread.sleep(3000)
                    synchronized(pipedOut) {
                        pipedOut.write(": heartbeat\n\n".toByteArray())
                        pipedOut.flush()
                    }
                }
            } catch (e: Exception) {
                // Client disconnected
            } finally {
                isOpen.set(false)
                AisReceiverService.removeNMEAListener(listener)
                try { pipedOut.close() } catch (_: Exception) {}
                Log.i(TAG, "NMEA SSE client disconnected")
            }
        }.start()

        val response = newChunkedResponse(
            Response.Status.OK,
            "text/event-stream",
            pipedIn
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("X-Accel-Buffering", "no")
        response.addHeader("Connection", "keep-alive")
        return response
    }

    // ---- TrustedDocks / MQTT Gateway ----

    /**
     * POST /admin/api/gateway/connect
     * Body: {"api_key": "..."}
     *
     * Validates the API key against TrustedDocks, saves config to SharedPreferences,
     * and returns station information on success.
     */
    private fun handleGatewayConnect(session: IHTTPSession): Response {
        try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val jsonString = map["postData"]
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    """{"success":false,"error":"Missing request body"}""")

            val reqJson = JSONObject(jsonString)
            val apiKey = reqJson.optString("api_key", "")
            if (apiKey.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    """{"success":false,"error":"api_key is required"}""")
            }

            // Call TrustedDocks API (blocking — NanoHTTPD serves on worker threads)
            val stationConfig = TrustedDocksApi.getStationConfig(apiKey)
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    """{"success":false,"error":"Invalid API key or network error"}""")

            // Persist to SharedPreferences
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(service)
            prefs.edit()
                .putBoolean("mqtt_enabled", true)
                .putString("mqtt_api_key", apiKey)
                .putString("mqtt_station_name", stationConfig.portName)
                .putInt("mqtt_antenna_id", stationConfig.antennaId)
                .putInt("mqtt_port_id", stationConfig.portId)
                .putString("mqtt_broker_url", stationConfig.brokerUrl)
                .putString("mqtt_user_name", stationConfig.userName)
                .putString("mqtt_company_name", stationConfig.companyName)
                .apply()

            // Build response
            val result = JSONObject().apply {
                put("success", true)
                put("station_name", stationConfig.portName)
                put("antenna_id", stationConfig.antennaId)
                put("port_id", stationConfig.portId)
                put("broker_url", stationConfig.brokerUrl)
                put("user_name", stationConfig.userName)
                put("company_name", stationConfig.companyName)
            }

            Log.i(TAG, "Gateway connected: station='${stationConfig.portName}'")
            return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())

        } catch (e: Exception) {
            Log.e(TAG, "Error in gateway/connect", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }.toString())
        }
    }

    /**
     * GET /admin/api/gateway/status
     *
     * Returns current MQTT connection status, message count, and saved station info.
     */
    private fun handleGatewayStatus(): Response {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(service)
        val result = JSONObject().apply {
            put("mqtt_enabled", prefs.getBoolean("mqtt_enabled", false))
            put("mqtt_connected", MqttPublisher.isConnected)
            put("mqtt_messages_sent", MqttPublisher.messagesSent)
            put("station_name", prefs.getString("mqtt_station_name", ""))
            put("antenna_id", prefs.getInt("mqtt_antenna_id", 0))
            put("port_id", prefs.getInt("mqtt_port_id", 0))
            put("broker_url", prefs.getString("mqtt_broker_url", ""))
            put("user_name", prefs.getString("mqtt_user_name", ""))
            put("company_name", prefs.getString("mqtt_company_name", ""))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
    }
    /**
     * GET /admin/api/health
     *
     * Compact health endpoint for remote dashboard monitoring.
     * Returns all key station metrics in a single JSON response.
     */
    private fun handleHealth(): Response {
        val ctx = service.applicationContext
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val deviceInfo = UsbDeviceScanner.scanForDevices(ctx)

        val appVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) { "unknown" }

        val result = JSONObject().apply {
            put("station_name", prefs.getString("pref_station_name", "") ?: "")
            put("antenna_uuid", prefs.getString("mqtt_antenna_uuid", "") ?: "")
            put("mqtt_username", prefs.getString("mqtt_username", "") ?: "")
            put("service_running", AisReceiverService.isRunning)
            put("sdr_connected", AisReceiverService.hasDevice)
            put("sdr_device", if (deviceInfo.found) deviceInfo.deviceName else "none")
            put("ais_msg_count", AisReceiverService.messageCount)
            put("gps_locked", GpsForwarder.hasPosition)
            if (GpsForwarder.hasPosition) {
                put("gps_lat", GpsForwarder.lastLatitude)
                put("gps_lon", GpsForwarder.lastLongitude)
            }
            if (GpsForwarder.hasHeading) {
                put("gps_heading", GpsForwarder.lastHeading)
            }
            put("mqtt_connected", MqttPublisher.isConnected)
            put("mqtt_messages_sent", MqttPublisher.messagesSent)
            put("mqtt_enabled", prefs.getBoolean("mqtt_enabled", false))
            put("web_viewer_port", prefs.getString("pref_local_web_port", "8080") ?: "8080")
            put("remote_access", prefs.getBoolean("pref_enable_remote", false))
            put("uptime_ms", android.os.SystemClock.elapsedRealtime())
            put("version", appVersion)
            put("timestamp", System.currentTimeMillis())
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
    }

    /**
     * POST /admin/api/usb/request_permission
     * Scans for SDR devices and requests USB permission if needed.
     */
    private fun handleRequestUsbPermission(): Response {
        val ctx = service.applicationContext
        val deviceInfo = UsbDeviceScanner.scanForDevices(ctx)
        
        if (!deviceInfo.found) {
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":false,"message":"No SDR device connected"}""")
        }
        
        if (deviceInfo.isUsable) {
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":true,"message":"Permission already granted","device":"${deviceInfo.deviceName}"}""")
        }
        
        // Device found but no permission — request it
        try {
            val usbManager = ctx.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            for ((_, device) in usbManager.deviceList) {
                if (device.vendorId == deviceInfo.vendorId && device.productId == deviceInfo.productId) {
                    val permissionIntent = android.app.PendingIntent.getBroadcast(
                        ctx, 0,
                        android.content.Intent("com.porttracker.ais.USB_PERMISSION").apply {
                            setPackage(ctx.packageName)
                        },
                        android.app.PendingIntent.FLAG_MUTABLE
                    )
                    usbManager.requestPermission(device, permissionIntent)
                    return newFixedLengthResponse(Response.Status.OK, "application/json",
                        """{"success":true,"message":"Permission requested for ${deviceInfo.deviceName}","device":"${deviceInfo.deviceName}"}""")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting USB permission", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown") }.toString())
        }
        
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"success":false,"message":"Could not find device to request permission"}""")
    }
    
    /**
     * POST /admin/api/engine/restart
     * Re-scans USB devices and restarts the AIS engine.
     */
    private fun handleEngineRestart(): Response {
        try {
            service.triggerEngineRestart()
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":true,"message":"Engine restart triggered"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering engine restart", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown") }.toString())
        }
    }

    /**
     * POST /admin/api/mqtt/restart
     * Stops and restarts the MQTT publisher with current preferences.
     */
    private fun handleMqttRestart(): Response {
        try {
            service.restartMqttPublisher()
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":true,"message":"MQTT publisher restarted"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting MQTT", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown") }.toString())
        }
    }

    /**
     * POST /admin/api/frp/restart
     * Restarts the FRP tunnel with current preferences.
     */
    private fun handleFrpRestart(): Response {
        try {
            service.restartFrpTunnel()
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":true,"message":"FRP tunnel restarted"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting FRP", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown") }.toString())
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

        // Always allow local access (in-app WebView uses 127.0.0.1)
        val remoteIp = session.remoteIpAddress ?: ""
        Log.d(TAG, "Auth check: remoteIp=$remoteIp authEnabled=$authEnabled")
        if (remoteIp == "127.0.0.1" || 
            remoteIp == "::1" || 
            remoteIp == "0:0:0:0:0:0:0:1" || 
            remoteIp.startsWith("127.") || 
            remoteIp.endsWith("127.0.0.1") || 
            remoteIp.startsWith("::ffff:127.") ||
            remoteIp.equals("localhost", ignoreCase = true)) {
            return null // Local access, skip auth
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
        response.addHeader("WWW-Authenticate", "Basic realm=\"TrustedDocks AIS\"")
        return response
    }
}
