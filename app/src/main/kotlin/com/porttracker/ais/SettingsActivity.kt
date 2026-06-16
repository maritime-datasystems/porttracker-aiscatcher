package com.porttracker.ais

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    private var webView: WebView? = null

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build layout programmatically
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // 1. Small native header bar with port config
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        // Port label
        val portLabel = TextView(this).apply {
            text = "Web Port: "
            textSize = 14f
        }
        headerLayout.addView(portLabel)

        // Port input
        val portInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            setText(prefs.getString("pref_local_web_port", "8080"))
            layoutParams = LinearLayout.LayoutParams(200, WRAP_CONTENT)
            textSize = 14f
        }
        headerLayout.addView(portInput)

        // Apply button
        val applyBtn = Button(this).apply {
            text = "Apply"
            textSize = 12f
            setOnClickListener {
                val newPort = portInput.text.toString().trim()
                if (newPort.isNotEmpty()) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    prefs.edit().putString("pref_local_web_port", newPort).apply()
                    // Reload WebView with new port
                    webView?.loadUrl("http://127.0.0.1:$newPort/")
                }
            }
        }
        headerLayout.addView(applyBtn)

        rootLayout.addView(headerLayout)

        // 2. WebView
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    // Web server not ready yet — retry after delay
                    view?.postDelayed({ loadWebUI() }, 2000)
                }
            }
            webChromeClient = WebChromeClient()
        }
        rootLayout.addView(webView)

        setContentView(rootLayout)

        // Check USB permission at startup
        Handler(Looper.getMainLooper()).postDelayed({
            checkAndRequestUsbPermission()
        }, AUTO_START_DELAY)

        // Auto-start service on app launch if enabled (with delay for USB enumeration)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val autoStartOnLaunch = prefs.getBoolean("auto_start_launch", true)

        if (autoStartOnLaunch && !AisReceiverService.isRunning) {
            // Delay 3 seconds to allow USB device enumeration and permission
            Handler(Looper.getMainLooper()).postDelayed({
                if (!AisReceiverService.isRunning) {
                    // Only start if SDR is connected and has permission
                    val deviceInfo = UsbDeviceScanner.scanForDevices(this)

                    if (deviceInfo.found && deviceInfo.isUsable) {
                        val intent = Intent(this, AisReceiverService::class.java).apply {
                            putExtra("USB_VENDOR_ID", deviceInfo.vendorId)
                            putExtra("USB_PRODUCT_ID", deviceInfo.productId)
                        }
                        startForegroundService(intent)
                    } else {
                        // Start in web-only mode — always needed for the WebView UI
                        val intent = Intent(this, AisReceiverService::class.java).apply {
                            putExtra("USB_VENDOR_ID", 0)
                            putExtra("USB_PRODUCT_ID", 0)
                        }
                        startForegroundService(intent)
                        Toast.makeText(this, "🌐 Starting web server (auto)...", Toast.LENGTH_SHORT).show()
                    }
                }
            }, BOOT_CHECK_DELAY)
        }

        // Handle USB device attached intent (from "Always open PortTracker" selection)
        handleUsbIntent(intent)

        // Ensure service is running for web server
        ensureServiceStarted()

        // Load web UI after a brief delay to let the server start
        webView?.postDelayed({ loadWebUI() }, 1000)
    }

    override fun onResume() {
        super.onResume()
        // Ensure webviewer_enabled is always true when this activity is active
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit().putBoolean("webviewer_enabled", true).apply()

        // Reload the WebView
        loadWebUI()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun loadWebUI() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val port = prefs.getString("pref_local_web_port", "8080") ?: "8080"
        webView?.loadUrl("http://127.0.0.1:$port/")
    }

    private fun ensureServiceStarted() {
        if (!AisReceiverService.isRunning) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            // Ensure web viewer is enabled
            prefs.edit().putBoolean("webviewer_enabled", true).apply()

            val deviceInfo = UsbDeviceScanner.scanForDevices(this)
            val intent = Intent(this, AisReceiverService::class.java).apply {
                if (deviceInfo.found && deviceInfo.isUsable) {
                    putExtra("USB_VENDOR_ID", deviceInfo.vendorId)
                    putExtra("USB_PRODUCT_ID", deviceInfo.productId)
                } else {
                    putExtra("USB_VENDOR_ID", 0)
                    putExtra("USB_PRODUCT_ID", 0)
                }
            }
            startForegroundService(intent)
        }
    }

    // =============== USB PERMISSION HANDLING ===============

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleUsbIntent(it) }
    }

    private fun handleUsbIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (device != null) {
                Log.i(TAG, "USB device attached via Intent: ${device.productName} (${device.vendorId}:${device.productId})")

                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

                if (usbManager.hasPermission(device)) {
                    // We have permission - this means user selected "Always open PortTracker"!
                    Log.i(TAG, "USB permission already granted (persistent)")
                    Toast.makeText(this, "✅ SDR connected with persistent permission", Toast.LENGTH_SHORT).show()

                    // Start the service with this device
                    if (!AisReceiverService.isRunning) {
                        val serviceIntent = Intent(this, AisReceiverService::class.java).apply {
                            putExtra("USB_VENDOR_ID", device.vendorId)
                            putExtra("USB_PRODUCT_ID", device.productId)
                        }
                        startForegroundService(serviceIntent)
                    }
                } else {
                    // Request permission - this time the "Always open" checkbox will be available!
                    Log.i(TAG, "Requesting USB permission with 'Always' option available")
                    val pendingIntent = PendingIntent.getBroadcast(
                        this, 0,
                        Intent(USB_PERMISSION_ACTION).apply {
                            putExtra(UsbManager.EXTRA_DEVICE, device)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    usbManager.requestPermission(device, pendingIntent)
                }
            }
        }
    }

    private fun checkAndRequestUsbPermission() {
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            val deviceInfo = UsbDeviceScanner.scanForDevices(this)

            if (deviceInfo.found && !deviceInfo.isUsable) {
                // SDR found but no permission - request it
                for ((_, device) in usbManager.deviceList) {
                    if (device.vendorId == deviceInfo.vendorId && device.productId == deviceInfo.productId) {
                        val pendingIntent = PendingIntent.getBroadcast(
                            this, 0,
                            Intent(USB_PERMISSION_ACTION),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        usbManager.requestPermission(device, pendingIntent)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore permission check errors
        }
    }

    // =============== SERVICE CONTROL ===============

    private fun startService() {
        val deviceInfo = UsbDeviceScanner.scanForDevices(this)

        if (!deviceInfo.found) {
            // No SDR - start in web-only mode (always needed for WebView UI)
            Toast.makeText(this, "🌐 Starting in web-only mode...", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(this, AisReceiverService::class.java).apply {
                    putExtra("USB_VENDOR_ID", 0)
                    putExtra("USB_PRODUCT_ID", 0)
                }
                startForegroundService(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (!deviceInfo.isUsable) {
            Toast.makeText(this, "⚠️ SDR needs permission - requesting...", Toast.LENGTH_SHORT).show()
            requestSdrPermission(deviceInfo)
            return
        }

        try {
            val intent = Intent(this, AisReceiverService::class.java).apply {
                putExtra("USB_VENDOR_ID", deviceInfo.vendorId)
                putExtra("USB_PRODUCT_ID", deviceInfo.productId)
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopService() {
        try {
            val intent = Intent(this, AisReceiverService::class.java)
            stopService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestSdrPermission(deviceInfo: UsbDeviceScanner.SdrDeviceInfo) {
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            for ((_, device) in usbManager.deviceList) {
                if (device.vendorId == deviceInfo.vendorId && device.productId == deviceInfo.productId) {
                    val pendingIntent = PendingIntent.getBroadcast(
                        this, 0,
                        Intent(USB_PERMISSION_ACTION),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    usbManager.requestPermission(device, pendingIntent)
                    return
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private companion object {
        const val TAG = "SettingsActivity"
        const val AUTO_START_DELAY = 1000L
        const val BOOT_CHECK_DELAY = 3000L
        const val USB_PERMISSION_ACTION = "com.porttracker.ais.USB_PERMISSION"
    }
}
