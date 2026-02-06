package com.porttracker.ais

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.jvdegithub.aiscatcher.AisCatcherJava
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service for running the AIS-catcher receiver in the background.
 */
class AisReceiverService : Service() {

    companion object {
        private const val TAG = "porttracker-service"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ais_receiver_channel"
        
        // Single lock to prevent concurrent native execution across service restarts
        private val serviceLock = java.util.concurrent.locks.ReentrantLock()

        // Callbacks from native code (called via AisCatcherJava bridge)
        @JvmStatic fun onNMEA(nmea: String) {
            Log.d(TAG, "NMEA: $nmea")
            incrementMessageCount()
            nmeaListeners.forEach { it.onNMEA(nmea) }
        }
        
        @JvmStatic fun onStatus(status: String) {
            Log.i(TAG, "Status: $status")
            statusListeners.forEach { it.onStatus(status) }
        }
        
        @JvmStatic fun onError(error: String) {
            Log.e(TAG, "Error: $error")
            errorListeners.forEach { it.onError(error) }
        }
        
        // Listener interfaces
        interface NMEAListener { fun onNMEA(nmea: String) }
        interface StatusListener { fun onStatus(status: String) }
        interface ErrorListener { fun onError(error: String) }
        
        private val nmeaListeners = mutableListOf<NMEAListener>()
        private val statusListeners = mutableListOf<StatusListener>()
        private val errorListeners = mutableListOf<ErrorListener>()
        
        fun addNMEAListener(l: NMEAListener) = nmeaListeners.add(l)
        fun removeNMEAListener(l: NMEAListener) = nmeaListeners.remove(l)
        
        // Service state
        @Volatile var isRunning = false
            private set
        
        @Volatile var hasDevice = false
            private set
        
        // Message counter for UI display
        private val _messageCount = java.util.concurrent.atomic.AtomicInteger(0)
        val messageCount: Int get() = _messageCount.get()
        
        fun incrementMessageCount() {
            _messageCount.incrementAndGet()
        }
        
        fun resetMessageCount() {
            _messageCount.set(0)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverThread: Thread? = null
    private val shouldStop = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)
    private var fileDescriptor: Int = -1
    private var currentSource: Int = 0
    private var gpsForwarder: GpsForwarder? = null
    private var frpTunnelManager: FrpTunnelManager? = null
    private var adminWebServer: AdminWebServer? = null
    private val startLock = Object()  // Prevent concurrent starts
    
    private val INTERNAL_WEB_PORT = 8888

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        extractWebAssets()
    }
    
    private fun extractWebAssets() {
        try {
            val assetManager = assets
            val filesList = assetManager.list("web") ?: return
            
            val webDir = java.io.File(filesDir, "web")
            if (!webDir.exists()) webDir.mkdirs()
            
            for (filename in filesList) {
                try {
                    val inStream = assetManager.open("web/$filename")
                    val outFile = java.io.File(webDir, filename)
                    val outStream = java.io.FileOutputStream(outFile)
                    
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (inStream.read(buffer).also { read = it } != -1) {
                        outStream.write(buffer, 0, read)
                    }
                    inStream.close()
                    outStream.close()
                    Log.i(TAG, "Extracted web asset: $filename")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract web asset: $filename", e)
                }
            }
        } catch (e: Exception) {
             Log.e(TAG, "Failed to list web assets", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(startLock) {
            // Prevent double-start
            if (isRunning && receiverThread?.isAlive == true) {
                Log.w(TAG, "Service already running, ignoring start command")
                return START_NOT_STICKY
            }
            
            Log.i(TAG, "Service starting...")
        
        // Get configuration from intent or preferences
        val config = loadConfig()
        currentSource = intent?.getIntExtra("SOURCE", config.deviceType.ordinal) ?: config.deviceType.ordinal
        
        
        // Open USB device - try intent first, then scan for SDR
        var vendorId = intent?.getIntExtra("USB_VENDOR_ID", -1) ?: -1
        var productId = intent?.getIntExtra("USB_PRODUCT_ID", -1) ?: -1
        
        // Fallback: scan for SDR device if not specified in intent
        if (vendorId <= 0 || productId <= 0) {
            Log.i(TAG, "No USB IDs in intent, scanning for SDR device...")
            val deviceInfo = UsbDeviceScanner.scanForDevices(this)
            if (deviceInfo.found && deviceInfo.isUsable) {
                vendorId = deviceInfo.vendorId
                productId = deviceInfo.productId
                Log.i(TAG, "Found SDR via scan: vendor=$vendorId, product=$productId")
            }
        }
        
        fileDescriptor = if (vendorId > 0 && productId > 0) {
            openUsbDevice(vendorId, productId)
        } else {
            -1
        }
        
        Log.i(TAG, "USB fd=$fileDescriptor (vendor=$vendorId, product=$productId)")
        
        // Start foreground with type for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification("Starting AIS Receiver..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Starting AIS Receiver..."))
        }
        
        // Acquire wake lock
        acquireWakeLock()
        
        // Start receiver in background thread
        shouldStop.set(false)
        receiverThread = Thread {
            runServiceLoop()
        }.apply {
            name = "AIS-Receiver-Thread"
            start()
        }
        
        // Start GPS forwarding if enabled
        if (config.gpsdEnabled) {
            gpsForwarder = GpsForwarder(this, config.gpsdHost, config.gpsdPort, config.gpsdInterval)
            if (gpsForwarder?.start() == true) {
                Log.i(TAG, "GPS forwarding started to ${config.gpsdHost}:${config.gpsdPort} (every ${config.gpsdInterval}s)")
            } else {
                Log.w(TAG, "GPS forwarding failed to start (check permissions)")
            }
        }
        
        // Check for Intent extras overrides (to handle race condition with SharedPreferences update)
        if (intent?.hasExtra("REMOTE_ENABLED") == true) {
            val overrideEnabled = intent.getBooleanExtra("REMOTE_ENABLED", false)
            val overrideName = intent.getStringExtra("STATION_NAME") ?: config.stationName
            Log.i(TAG, "Override config from intent: remote=$overrideEnabled, station='$overrideName'")
            
            // Create a copy of config with overrides (using data class copy)
            // Note: Since config is immutable, we just use local variables for logic below
            // Ideally we should update the config object, but here we just govern the logic
            
            // Start/Stop FRP tunnel based on OVERRIDE
            if (frpTunnelManager == null) {
                frpTunnelManager = FrpTunnelManager(this)
            }
            
            if (overrideEnabled && overrideName.isNotEmpty()) {
                if (frpTunnelManager?.isRunning() != true) {
                    Log.i(TAG, "Starting FRP tunnel (Override) for $overrideName...")
                    if (frpTunnelManager?.startTunnel(overrideName, config.webViewerPort) == true) {
                        Log.i(TAG, "FRP tunnel started")
                    }
                }
            } else {
                if (frpTunnelManager?.isRunning() == true) {
                    Log.i(TAG, "Stopping FRP tunnel (Override)")
                    frpTunnelManager?.stopTunnel()
                }
            }
        } else {
            // Standard Start/Stop based on SharedPreferences Config
            Log.i(TAG, "FRP config: remoteEnabled=${config.remoteAccessEnabled}, stationName='${config.stationName}'")
            
            if (frpTunnelManager == null) {
                frpTunnelManager = FrpTunnelManager(this)
            }
            
            if (config.remoteAccessEnabled && config.stationName.isNotEmpty()) {
                if (frpTunnelManager?.isRunning() != true) {
                    Log.i(TAG, "Starting FRP tunnel for ${config.stationName}...")
                    if (frpTunnelManager?.startTunnel(config.stationName, config.webViewerPort) == true) {
                        Log.i(TAG, "FRP tunnel started for station: ${config.stationName}")
                    } else {
                        Log.w(TAG, "FRP tunnel failed to start")
                    }
                }
            } else {
                if (frpTunnelManager?.isRunning() == true) {
                    Log.i(TAG, "Stopping FRP tunnel (disabled)")
                    frpTunnelManager?.stopTunnel()
                }
            }
        }
        
        isRunning = true
        } // end synchronized(startLock)
        return START_NOT_STICKY  // Don't auto-restart without USB IDs
    }
    
    private var usbConnection: android.hardware.usb.UsbDeviceConnection? = null
    
    private fun openUsbDevice(vendorId: Int, productId: Int): Int {
        try {
            val usbManager = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
            for ((_, device) in usbManager.deviceList) {
                if (device.vendorId == vendorId && device.productId == productId) {
                    if (usbManager.hasPermission(device)) {
                        usbConnection = usbManager.openDevice(device)
                        if (usbConnection != null) {
                            Log.i(TAG, "USB device opened successfully: ${device.productName}")
                            return usbConnection!!.fileDescriptor
                        }
                    } else {
                        Log.w(TAG, "No USB permission for device")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening USB device", e)
        }
        return -1
    }

    fun triggerEngineRestart() {
        Log.i(TAG, "Triggering engine restart via Admin API...")
        restartRequested.set(true)
        try {
            AisCatcherJava.forceStop()
        } catch (e: Exception) {
            Log.e(TAG, "Error forcing stop for restart", e)
        }
    }

    private fun runServiceLoop() {
        Log.i(TAG, "Service Loop Started")
        
        try {
            // Only run ONCE per process - no retry loop for native engine
            // If the native engine crashes/disconnects, we restart the whole service
            serviceLock.lock()
            try {
                restartRequested.set(false)
                
                // Reload config fresh
                val config = loadConfig()
                
                // Manage Admin Web Server
                if (config.webViewerEnabled) {
                     if (adminWebServer == null) {
                         Log.i(TAG, "Starting Admin Web Server on port ${config.webViewerPort}...")
                         adminWebServer = AdminWebServer(this, config.webViewerPort, INTERNAL_WEB_PORT)
                         adminWebServer?.start()
                     }
                } else {
                    adminWebServer?.stop()
                    adminWebServer = null
                }
                
                Log.i(TAG, "Starting Native Engine...")
                isRunning = true
                runNativeEngine(config, currentSource, fileDescriptor)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in Service Loop", e)
            } finally {
                serviceLock.unlock()
            }
            
            // Engine exited - decide what to do
            if (shouldStop.get()) {
                Log.i(TAG, "Service loop exiting (stopped by user)")
                return
            }
            
            if (restartRequested.get()) {
                Log.i(TAG, "Engine restart requested (config change)")
                scheduleServiceRestart(2000)
                return
            }
            
            // Unexpected exit (USB disconnect, crash, etc.)
            // Schedule a full service restart in a new process
            Log.w(TAG, "Engine exited unexpectedly. Scheduling service restart in 5s...")
            scheduleServiceRestart(5000)
            
        } finally {
            isRunning = false
            Log.i(TAG, "Service Loop Ended")
        }
    }
    
    /**
     * Schedule a full service restart. This creates a clean native library state
     * by stopping the current service and starting a new one after a delay.
     * This avoids the SIGSEGV that occurs when re-calling InitNative in the same process.
     */
    private fun scheduleServiceRestart(delayMs: Long) {
        Log.i(TAG, "Scheduling service restart in ${delayMs}ms...")
        
        val restartIntent = Intent(this, AisReceiverService::class.java).apply {
            putExtra("USB_VENDOR_ID", 0)   // Will be re-scanned
            putExtra("USB_PRODUCT_ID", 0)  // Will be re-scanned
        }
        
        val pendingIntent = android.app.PendingIntent.getForegroundService(
            this, 42, restartIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val alarmManager = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + delayMs,
            pendingIntent
        )
        
        // Stop this service (kills the process, freeing native resources)
        stopSelf()
    }

    private fun runNativeEngine(config: ServiceConfig, source: Int, fd: Int) {
        try {
            if (!AisCatcherJava.isLibraryLoaded) {
                val error = "Native library not loaded - cannot start receiver"
                Log.e(TAG, error)
                updateNotification(error)
                onError(error)
                return
            }

            Log.i(TAG, "Initializing native library...")
            
            // Force close first to ensure clean state on restart
            try {
                AisCatcherJava.Close()
                Thread.sleep(500)  // Allow port to be released
            } catch (e: Exception) {
                Log.d(TAG, "Pre-init close (expected on first start): ${e.message}")
            }
            
            AisCatcherJava.InitNative(INTERNAL_WEB_PORT, "127.0.0.1")
            
            // Initialize statistics class - REQUIRED before Run()
            Log.i(TAG, "Initializing statistics...")
            AisCatcherJava.Statistics.Init()
            
            // Configure UDP outputs (up to 4)
            config.udpOutputs.forEachIndexed { index, udp ->
                if (udp.enabled) {
                    Log.i(TAG, "Creating UDP output ${index + 1}: ${udp.host}:${udp.port} (json=${udp.json})")
                    AisCatcherJava.createUDP(udp.host, udp.port.toString(), udp.json)
                }
            }
            
            if (config.tcpEnabled) {
                Log.i(TAG, "Creating TCP listener on port ${config.tcpPort}")
                AisCatcherJava.createTCPlistener(config.tcpPort.toString())
            }
            
            // Start Community Hub sharing
            if (config.hubEnabled && config.hubKey.isNotEmpty()) {
                Log.i(TAG, "Starting Community Hub sharing with key: ${config.hubKey.take(8)}...")
                AisCatcherJava.createSharing(true, config.hubKey)
            }
            
            // Start web viewer (Internal C++ Server)
            if (config.webViewerEnabled) {
                Log.i(TAG, "Creating Internal WebViewer on port $INTERNAL_WEB_PORT")
                AisCatcherJava.createWebViewer(INTERNAL_WEB_PORT.toString())
                
                // External Admin/Proxy Server is managed in runServiceLoop
            }
            
            // Try to create receiver (SDR is always attempted if device available)
            Log.i(TAG, "Creating receiver (source=$source, fd=$fd)")
            val result = AisCatcherJava.createReceiver(source, fd, 0, 0, 0)
            
            if (result == 0) {
                hasDevice = true
                val statusMsg = if (config.webViewerEnabled) {
                    "AIS Receiver running (Web: port ${config.webViewerPort})"
                } else {
                    "AIS Receiver running"
                }
                updateNotification(statusMsg)
                Log.i(TAG, "Running receiver...")
                AisCatcherJava.Run() // This blocks until stopped
                Log.i(TAG, "Receiver stopped")
            } else {
                hasDevice = false
                if (config.webViewerEnabled) {
                    Log.w(TAG, "No SDR device available (result=$result), web interface running")
                    updateNotification("No SDR device - Web only (port ${config.webViewerPort})")
                    
                    // Web server was already created by createWebViewer() and runs on its own thread
                    // Do NOT call Run() here - it crashes (SIGSEGV) without a valid SDR device
                    // Just keep the service alive so the web server can continue serving
                    Log.i(TAG, "Starting in web-only mode (keep-alive loop)...")
                    while (!shouldStop.get() && !restartRequested.get()) {
                        try {
                            Thread.sleep(1000)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                    Log.i(TAG, "Web-only mode stopped")
                } else {
                    Log.e(TAG, "No SDR device available and web interface disabled - stopping service")
                    updateNotification("No SDR device - Service stopping")
                    // No SDR and no web - nothing to do
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Receiver fatal error", t)
            updateNotification("Error: ${t.message}")
            onError(t.message ?: "Unknown error")
        } finally {
            if (AisCatcherJava.isLibraryLoaded) {
                try {
                    AisCatcherJava.Close()
                    // Brief delay for port release
                    Thread.sleep(500)
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing receiver", e)
                }
            }
            // isRunning = false  <- MOVED to runServiceLoop
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service stopping...")
        
        shouldStop.set(true)
        try {
            AisCatcherJava.forceStop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping receiver", e)
        }
        
        receiverThread?.let {
            it.interrupt()
            try {
                it.join(5000)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted waiting for receiver thread")
            }
        }
        
        // Stop GPS forwarding
        try {
            gpsForwarder?.stop()
            gpsForwarder = null
            Log.i(TAG, "GPS forwarder stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping GPS forwarder", e)
        }
        
        // Stop FRP tunnel
        try {
            frpTunnelManager?.stopTunnel()
            frpTunnelManager = null
            Log.i(TAG, "FRP tunnel stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping FRP tunnel", e)
        }
        
        // Stop Admin Web Server
        try {
            adminWebServer?.stop()
            adminWebServer = null
            Log.i(TAG, "Admin Web Server stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Admin Web Server", e)
        }
        
        // Close USB connection to release device for re-enumeration
        try {
            usbConnection?.close()
            usbConnection = null
            Log.i(TAG, "USB connection closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing USB connection", e)
        }
        
        releaseWakeLock()
        isRunning = false
        hasDevice = false
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadConfig(): ServiceConfig {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        
        // Load UDP outputs
        val udpOutputs = (1..4).map { i ->
            UdpOutput(
                enabled = prefs.getBoolean("udp${i}_enabled", false),
                host = prefs.getString("udp${i}_host", "127.0.0.1") ?: "127.0.0.1",
                port = prefs.getString("udp${i}_port", (10109 + i).toString())?.toIntOrNull() ?: (10109 + i),
                json = prefs.getBoolean("udp${i}_json", false)
            )
        }
        
        return ServiceConfig(
            deviceType = DeviceType.entries[prefs.getString("device_type", "1")?.toIntOrNull() ?: 1],
            frequencyCorrection = prefs.getString("frequency_correction", "0")?.toIntOrNull() ?: 0,
            udpOutputs = udpOutputs,
            tcpEnabled = prefs.getBoolean("tcp_enabled", false),
            tcpPort = prefs.getString("tcp_port", "10111")?.toIntOrNull() ?: 10111,
            webViewerEnabled = prefs.getBoolean("webviewer_enabled", false),
            webViewerPort = prefs.getString("pref_local_web_port", "8080")?.toIntOrNull() ?: 8080,
            webServerPort = prefs.getString("pref_local_web_port", "8080")?.toIntOrNull() ?: 8080,
            gpsdEnabled = prefs.getBoolean("gpsd_enabled", false),
            gpsdHost = prefs.getString("gpsd_host", "127.0.0.1") ?: "127.0.0.1",
            gpsdPort = prefs.getString("gpsd_port", "2947")?.toIntOrNull() ?: 2947,
            gpsdInterval = prefs.getString("gpsd_interval", "10")?.toIntOrNull() ?: 10,
            hubEnabled = prefs.getBoolean("hub_sharing", false),
            hubKey = prefs.getString("hub_key", "") ?: "",
            remoteAccessEnabled = prefs.getBoolean("pref_enable_remote", false),
            stationName = prefs.getString("pref_station_name", "") ?: ""
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "porttracker-aiscatcher",
                NotificationManager.IMPORTANCE_MIN  // Minimum importance - no sound, no popup
            ).apply {
                description = "AIS Receiver service status"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("porttracker-aiscatcher")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)  // Prevent sound/vibration
            .setPriority(NotificationCompat.PRIORITY_LOW)  // Minimize interruption
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "porttracker-aiscatcher:WakeLock")
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}

/**
 * UDP output configuration
 */
data class UdpOutput(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 10110,
    val json: Boolean = false
)

/**
 * Service configuration
 */
data class ServiceConfig(
    val deviceType: DeviceType = DeviceType.RTLSDR,
    val frequencyCorrection: Int = 0,
    val udpOutputs: List<UdpOutput> = emptyList(),
    val tcpEnabled: Boolean = false,
    val tcpPort: Int = 10111,
    val webViewerEnabled: Boolean = true,
    val webViewerPort: Int = 8080,
    val webServerPort: Int = 8080,
    val gpsdEnabled: Boolean = false,
    val gpsdHost: String = "127.0.0.1",
    val gpsdPort: Int = 2947,
    val gpsdInterval: Int = 10,
    val hubEnabled: Boolean = false,
    val hubKey: String = "",
    val remoteAccessEnabled: Boolean = false,
    val stationName: String = ""
)

/**
 * SDR device types
 */
enum class DeviceType {
    RTLTCP,
    RTLSDR,
    AIRSPY,
    AIRSPYHF,
    SPYSERVER
}
