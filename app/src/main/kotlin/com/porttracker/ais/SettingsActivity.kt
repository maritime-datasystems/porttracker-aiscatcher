package com.porttracker.ais

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Top-level extension to set up an EditTextPreference with a simple summary provider.
 * Used by both SettingsFragment and NetworkingFragment to avoid duplication.
 */
private fun PreferenceFragmentCompat.setupEditTextPreferenceSummary(key: String) {
    findPreference<EditTextPreference>(key)?.apply {
        summary = text ?: ""
        setOnPreferenceChangeListener { pref, newValue ->
            pref.summary = newValue.toString()
            true
        }
    }
}

/** Pre-compiled regexes for station name sanitization. */
private val SANITIZE_NON_ALNUM = Regex("[^a-z0-9-]")
private val SANITIZE_MULTI_DASH = Regex("-+")

/** Sanitize a station name to a URL-safe slug. */
private fun sanitizeStationName(name: String): String {
    return name.trim().lowercase()
        .replace(SANITIZE_NON_ALNUM, "-")
        .replace(SANITIZE_MULTI_DASH, "-")
        .trimEnd('-')
}

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = SettingsPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Status"
                1 -> "Settings"
                2 -> "Control"
                3 -> "Web"
                else -> ""
            }
        }.attach()

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
                    val webViewerEnabled = prefs.getBoolean("webviewer_enabled", false)
                    
                    if (deviceInfo.found && deviceInfo.isUsable) {
                        val intent = Intent(this, AisReceiverService::class.java).apply {
                            putExtra("USB_VENDOR_ID", deviceInfo.vendorId)
                            putExtra("USB_PRODUCT_ID", deviceInfo.productId)
                        }
                        startForegroundService(intent)
                    } else if (webViewerEnabled) {
                        // Start in web-only mode if enabled
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
    }
    
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

    private inner class SettingsPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> StatusFragment()
                1 -> SettingsFragment()
                2 -> ControlFragment()
                3 -> NetworkingFragment()
                else -> StatusFragment()
            }
        }
    }

    // =============== STATUS FRAGMENT ===============
    class StatusFragment : PreferenceFragmentCompat() {
        private val handler = Handler(Looper.getMainLooper())
        private var refreshRunnable: Runnable? = null
        
        private val locationPermissionLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val granted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (granted) {
                Toast.makeText(context, "✅ Location permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "❌ Location permission denied", Toast.LENGTH_SHORT).show()
            }
            updateGpsStatus()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_status, rootKey)
            
            // Click device_status to request USB permission
            findPreference<Preference>("device_status")?.setOnPreferenceClickListener {
                requestUsbPermission()
                true
            }
            
            // Click gps_permission to request location permission
            findPreference<Preference>("gps_permission")?.setOnPreferenceClickListener {
                requestLocationPermission()
                true
            }
            
            updateAllStatus()
        }
        
        private fun requestUsbPermission() {
            try {
                val ctx = context ?: return
                val usbManager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
                if (usbManager == null) {
                    Toast.makeText(ctx, "USB Manager not available", Toast.LENGTH_SHORT).show()
                    return
                }
                
                // Find the SDR device specifically
                val deviceInfo = UsbDeviceScanner.scanForDevices(ctx)
                if (!deviceInfo.found) {
                    Toast.makeText(ctx, "No SDR device detected", Toast.LENGTH_SHORT).show()
                    return
                }
                
                if (deviceInfo.isUsable) {
                    Toast.makeText(ctx, "Permission already granted. Restart service.", Toast.LENGTH_SHORT).show()
                    return
                }
                
                // Find the matching USB device and request permission
                for ((_, device) in usbManager.deviceList) {
                    if (device.vendorId == deviceInfo.vendorId && device.productId == deviceInfo.productId) {
                        val pendingIntent = PendingIntent.getBroadcast(
                            ctx, 0,
                            Intent(USB_PERMISSION_ACTION),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        usbManager.requestPermission(device, pendingIntent)
                        Toast.makeText(ctx, "Requesting permission for ${deviceInfo.deviceName}...", Toast.LENGTH_SHORT).show()
                        return
                    }
                }
                
                Toast.makeText(ctx, "SDR device not found in USB list", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onResume() {
            super.onResume()
            startPeriodicRefresh()
        }

        override fun onPause() {
            super.onPause()
            stopPeriodicRefresh()
        }

        private fun startPeriodicRefresh() {
            refreshRunnable = object : Runnable {
                override fun run() {
                    if (isAdded) {
                        updateAllStatus()
                        handler.postDelayed(this, STATUS_REFRESH_INTERVAL)
                    }
                }
            }
            handler.post(refreshRunnable!!)
        }

        private fun stopPeriodicRefresh() {
            refreshRunnable?.let { handler.removeCallbacks(it) }
        }

        private fun updateAllStatus() {
            if (!isAdded) return
            updateVersionInfo()
            updateNetworkStatus()
            updateServiceStatus()
            updateMessageCount()
            updateGpsStatus()
            updateUsbDebug()
        }

        private fun updateVersionInfo() {
            try {
                val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
                findPreference<Preference>("app_version")?.summary = pInfo.versionName
            } catch (e: Exception) {
                findPreference<Preference>("app_version")?.summary = "Unknown"
            }
        }

        private fun updateNetworkStatus() {
            findPreference<Preference>("local_ip")?.summary = NetworkUtils.getLocalIpAddress(requireContext())
            findPreference<Preference>("network_status")?.summary = 
                if (NetworkUtils.isNetworkAvailable(requireContext())) "Connected" else "Disconnected"
            
            // Update external IP in background using callback
            NetworkUtils.getExternalIpAddress { externalIp ->
                handler.post {
                    if (isAdded) {
                        findPreference<Preference>("external_ip")?.summary = externalIp
                    }
                }
            }
        }

        private fun updateServiceStatus() {
            findPreference<Preference>("service_status")?.summary = 
                if (AisReceiverService.isRunning) "🟢 Running" else "🔴 Stopped"
            
            // Show actual SDR device status with more detail
            val ctx = context ?: return
            val deviceInfo = UsbDeviceScanner.scanForDevices(ctx)
            val deviceSummary = when {
                AisReceiverService.hasDevice -> "✅ ${deviceInfo.deviceName} - Active"
                deviceInfo.found && deviceInfo.isUsable -> "🔌 ${deviceInfo.deviceName} - Ready (restart to connect)"
                deviceInfo.found && !deviceInfo.isUsable -> "⚠️ ${deviceInfo.deviceName} - Tap to grant permission"
                else -> "❌ No SDR device connected"
            }
            findPreference<Preference>("device_status")?.summary = deviceSummary
        }

        private fun updateMessageCount() {
            findPreference<Preference>("msg_count")?.summary = "${AisReceiverService.messageCount}"
        }

        private fun updateGpsStatus() {
            val ctx = context ?: return
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val gpsdEnabled = prefs.getBoolean("gpsd_enabled", false)
            
            // Check location permission
            val hasLocationPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            val permPref = findPreference<Preference>("gps_permission")
            if (hasLocationPermission) {
                permPref?.summary = "✅ Granted"
                permPref?.isSelectable = false
            } else {
                permPref?.summary = "❌ Not granted — Tap to request"
                permPref?.isSelectable = true
            }
            
            if (!gpsdEnabled) {
                findPreference<Preference>("gps_status")?.summary = "❌ Disabled in Settings"
                findPreference<Preference>("gps_coordinates")?.summary = "—"
                findPreference<Preference>("gps_msg_count")?.summary = "—"
            } else if (!hasLocationPermission) {
                findPreference<Preference>("gps_status")?.summary = "⚠️ Needs permission"
                findPreference<Preference>("gps_coordinates")?.summary = "—"
                findPreference<Preference>("gps_msg_count")?.summary = "—"
            } else {
                // GPS is enabled in settings and has permission
                val statusText = if (GpsForwarder.isForwarding) "🟢 Forwarding" else "⏸️ Waiting for service"
                findPreference<Preference>("gps_status")?.summary = statusText
                
                // Show coordinates and heading
                val coordText = if (GpsForwarder.hasPosition) {
                    val base = String.format("%.6f, %.6f", GpsForwarder.lastLatitude, GpsForwarder.lastLongitude)
                    if (GpsForwarder.hasHeading) {
                        "$base  🧭 ${String.format("%.0f", GpsForwarder.lastHeading)}°"
                    } else {
                        base
                    }
                } else {
                    "📍 Acquiring..."
                }
                findPreference<Preference>("gps_coordinates")?.summary = coordText
                
                // Show message count from last minute
                findPreference<Preference>("gps_msg_count")?.summary = "${GpsForwarder.messagesLastMinute}"
            }
        }
        
        private fun requestLocationPermission() {
            val ctx = context ?: return
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (hasPermission) {
                Toast.makeText(ctx, "✅ Location permission already granted", Toast.LENGTH_SHORT).show()
                return
            }
            
            locationPermissionLauncher.launch(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }

        private fun updateUsbDebug() {
            try {
                val ctx = context ?: return
                val summary = UsbDeviceScanner.getAllUsbDevices(ctx)
                findPreference<Preference>("usb_devices")?.summary = summary
            } catch (e: Exception) {
                findPreference<Preference>("usb_devices")?.summary = "Error scanning"
            }
        }
    }

    // =============== SETTINGS FRAGMENT ===============
    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_settings, rootKey)
            
            // Setup EditTextPreference summary updates
            for (i in 1..4) {
                setupEditTextPreferenceSummary("udp${i}_host")
                setupEditTextPreferenceSummary("udp${i}_port")
            }
            setupEditTextPreferenceSummary("gpsd_host")
            setupEditTextPreferenceSummary("gpsd_port")
            setupListPreferenceSummary("gpsd_interval")
            setupListPreferenceSummary("device_type")
            setupEditTextPreferenceSummary("tcp_port")
            setupEditTextPreferenceSummary("webviewer_port")
            setupEditTextPreferenceSummary("frequency_correction")
            
            // Battery Optimization preference - opens Android settings
            findPreference<Preference>("battery_optimization")?.setOnPreferenceClickListener {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to app-specific battery settings
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:${requireContext().packageName}")
                        }
                        startActivity(intent)
                    } catch (e2: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Could not open battery settings. Please disable optimization manually in Settings > Apps > TrustedDocks AIS.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                true
            }
            
            // Update battery optimization status
            updateBatteryOptimizationStatus()
        }
        
        private fun updateBatteryOptimizationStatus() {
            val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoringBattery = pm.isIgnoringBatteryOptimizations(requireContext().packageName)
            
            findPreference<Preference>("battery_optimization")?.summary = if (isIgnoringBattery) {
                "✅ Battery optimization disabled (recommended for remote stations)"
            } else {
                "⚠️ Tap to disable battery optimization (keeps service running)"
            }
        }
        
        override fun onResume() {
            super.onResume()
            updateBatteryOptimizationStatus()
        }

        // setupEditTextPreferenceSummary is now a top-level extension function

        private fun setupListPreferenceSummary(key: String) {
            findPreference<ListPreference>(key)?.apply {
                summary = entry ?: ""
                setOnPreferenceChangeListener { pref, newValue ->
                    val listPref = pref as ListPreference
                    val index = listPref.findIndexOfValue(newValue.toString())
                    if (index >= 0) {
                        pref.summary = listPref.entries[index]
                    }
                    true
                }
            }
        }
    }

    // =============== CONTROL FRAGMENT ===============
    class ControlFragment : PreferenceFragmentCompat() {
        private val handler = Handler(Looper.getMainLooper())
        private var refreshRunnable: Runnable? = null
        private var isStoppingService = false
        private var userInteractingUntil = 0L  // Debounce for user interaction

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_control, rootKey)

            // Service control switch
            findPreference<SwitchPreferenceCompat>("service_enabled")?.apply {
                isChecked = AisReceiverService.isRunning
                setOnPreferenceChangeListener { _, newValue ->
                    Log.d(TAG, "Switch changed to: $newValue")
                    // Set debounce - don't let status updates override for 3 seconds
                    userInteractingUntil = System.currentTimeMillis() + 3000
                    
                    if (newValue as Boolean) {
                        Log.d(TAG, "Calling startService()")
                        startService()
                    } else {
                        Log.d(TAG, "Calling stopService()")
                        stopService()
                    }
                    false // Prevent auto-commit, we control the state manually
                }
            }

            // Force Stop button
            findPreference<Preference>("force_stop")?.setOnPreferenceClickListener {
                forceStopService()
                true
            }

            // Shutdown button
            findPreference<Preference>("shutdown_app")?.setOnPreferenceClickListener {
                shutdownApp()
                true
            }
            
            updateStatus()
        }

        override fun onResume() {
            super.onResume()
            startPeriodicRefresh()
        }
        
        override fun onPause() {
            super.onPause()
            stopPeriodicRefresh()
        }
        
        private fun startPeriodicRefresh() {
            refreshRunnable = object : Runnable {
                override fun run() {
                    if (isAdded) {
                        updateStatus()
                        handler.postDelayed(this, CONTROL_REFRESH_INTERVAL)
                    }
                }
            }
            handler.post(refreshRunnable!!)
        }
        
        private fun stopPeriodicRefresh() {
            refreshRunnable?.let { handler.removeCallbacks(it) }
        }

        private fun updateStatus() {
            if (!isAdded) return
            
            // Update status text
            val statusText = if (AisReceiverService.isRunning) "🟢 Running" else "🔴 Stopped"
            findPreference<Preference>("control_status")?.summary = statusText
            
            // Only update switch if not during user interaction debounce
            if (System.currentTimeMillis() > userInteractingUntil) {
                findPreference<SwitchPreferenceCompat>("service_enabled")?.isChecked = 
                    AisReceiverService.isRunning
            }
        }

        private fun startService() {
            val ctx = context ?: return
            
            if (isStoppingService) {
                Toast.makeText(ctx, "⏳ Wait 3 seconds...", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Check if SDR device is available and has permission
            val deviceInfo = UsbDeviceScanner.scanForDevices(ctx)
            
            if (!deviceInfo.found) {
                // No SDR - check if web viewer is enabled for web-only mode
                val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val webEnabled = prefs.getBoolean("webviewer_enabled", false)
                
                if (webEnabled) {
                    // Start in web-only mode
                    Toast.makeText(ctx, "🌐 Starting in web-only mode...", Toast.LENGTH_SHORT).show()
                    try {
                        val intent = Intent(ctx, AisReceiverService::class.java).apply {
                            putExtra("USB_VENDOR_ID", 0)
                            putExtra("USB_PRODUCT_ID", 0)
                        }
                        ctx.startForegroundService(intent)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(ctx, "❌ No SDR device connected", Toast.LENGTH_SHORT).show()
                }
                return
            }
            
            if (!deviceInfo.isUsable) {
                Toast.makeText(ctx, "⚠️ SDR needs permission - requesting...", Toast.LENGTH_SHORT).show()
                // Request permission
                requestSdrPermission(ctx, deviceInfo)
                return
            }
            
            try {
                val intent = Intent(ctx, AisReceiverService::class.java).apply {
                    putExtra("USB_VENDOR_ID", deviceInfo.vendorId)
                    putExtra("USB_PRODUCT_ID", deviceInfo.productId)
                }
                ctx.startForegroundService(intent)
                // No toast - switch state change is enough feedback
            } catch (e: Exception) {
                Toast.makeText(ctx, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        private fun requestSdrPermission(ctx: Context, deviceInfo: UsbDeviceScanner.SdrDeviceInfo) {
            try {
                val usbManager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
                for ((_, device) in usbManager.deviceList) {
                    if (device.vendorId == deviceInfo.vendorId && device.productId == deviceInfo.productId) {
                        val pendingIntent = PendingIntent.getBroadcast(
                            ctx, 0,
                            Intent(USB_PERMISSION_ACTION),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        usbManager.requestPermission(device, pendingIntent)
                        return
                    }
                }
            } catch (e: Exception) { }
        }

        private fun stopService() {
            val ctx = context ?: return
            isStoppingService = true
            
            try {
                val intent = Intent(ctx, AisReceiverService::class.java)
                ctx.stopService(intent)
                // No toast - switch state change is enough feedback
            } catch (e: Exception) {
                Toast.makeText(ctx, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            
            // Clear cooldown after 1 second
            handler.postDelayed({ isStoppingService = false }, SERVICE_STOP_COOLDOWN)
        }

        private fun forceStopService() {
            val ctx = context ?: return
            isStoppingService = true
            
            try {
                val intent = Intent(ctx, AisReceiverService::class.java)
                ctx.stopService(intent)
                com.jvdegithub.aiscatcher.AisCatcherJava.Close()
            } catch (e: Exception) { }
            
            Toast.makeText(ctx, "⛔ Force stopped", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ isStoppingService = false }, FORCE_STOP_DELAY)
        }

        private fun shutdownApp() {
            val ctx = context ?: return
            Toast.makeText(ctx, "🔌 Shutting down...", Toast.LENGTH_SHORT).show()
            
            try {
                val intent = Intent(ctx, AisReceiverService::class.java)
                ctx.stopService(intent)
                com.jvdegithub.aiscatcher.AisCatcherJava.Close()
            } catch (e: Exception) { }
            
            handler.postDelayed({
                activity?.finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }, SHUTDOWN_DELAY)
        }
    }

    // =============== WEB FRAGMENT ===============
    class NetworkingFragment : PreferenceFragmentCompat() {
        private var remoteStatusPref: Preference? = null
        private var stationNamePref: EditTextPreference? = null
        private var enableRemotePref: SwitchPreferenceCompat? = null
        private var localWebStatusPref: Preference? = null
        private var webViewerEnabledPref: SwitchPreferenceCompat? = null
        private var localWebPortPref: EditTextPreference? = null
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_networking, rootKey)

            setupEditTextPreferenceSummary("dns_primary")
            setupEditTextPreferenceSummary("dns_secondary")
            setupEditTextPreferenceSummary("pref_porttracker_username")
            setupEditTextPreferenceSummary("pref_station_name")
            setupEditTextPreferenceSummary("pref_local_web_port")
            
            // Setup local web status
            localWebStatusPref = findPreference("pref_local_web_status")
            webViewerEnabledPref = findPreference("webviewer_enabled")
            localWebPortPref = findPreference("pref_local_web_port")
            
            // Update local web status and start/stop service when switch changes
            webViewerEnabledPref?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val port = localWebPortPref?.text ?: "8080"
                updateLocalWebStatus(enabled, port)
                
                val ctx = context ?: return@setOnPreferenceChangeListener true
                
                if (enabled) {
                    // Start service in web-only mode
                    if (!AisReceiverService.isRunning) {
                        Toast.makeText(ctx, "🌐 Starting web server...", Toast.LENGTH_SHORT).show()
                        try {
                            val intent = Intent(ctx, AisReceiverService::class.java).apply {
                                putExtra("USB_VENDOR_ID", 0)
                                putExtra("USB_PRODUCT_ID", 0)
                            }
                            ctx.startForegroundService(intent)
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    // Stop web service if running (but only if SDR is also not active)
                    if (AisReceiverService.isRunning && !AisReceiverService.hasDevice) {
                        Toast.makeText(ctx, "Stopping web server...", Toast.LENGTH_SHORT).show()
                        ctx.stopService(Intent(ctx, AisReceiverService::class.java))
                    }
                }
                true
            }
            
            // Update local web status when port changes
            localWebPortPref?.setOnPreferenceChangeListener { pref, newValue ->
                pref.summary = newValue.toString()
                updateLocalWebStatus(webViewerEnabledPref?.isChecked == true, newValue.toString())
                true
            }

            // Handle clicks on local web status URL
            localWebStatusPref?.setOnPreferenceClickListener {
                val enabled = webViewerEnabledPref?.isChecked == true
                val port = localWebPortPref?.text ?: "8080"
                
                if (enabled) {
                    val url = "http://localhost:$port"
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    startActivity(intent)
                } else {
                    val ctx = context ?: return@setOnPreferenceClickListener true
                    Toast.makeText(ctx, "Local web server is not enabled", Toast.LENGTH_SHORT).show()
                }
                true
            }

            // Show logs
            findPreference<Preference>("pref_show_debug_logs")?.setOnPreferenceClickListener {
                val logs = InternalLog.getLogs()
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Debug Logs")
                    .setMessage(if (logs.isEmpty()) "No logs available" else logs)
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Clear") { _, _ -> 
                        InternalLog.clear()
                        context?.let { Toast.makeText(it, "Logs cleared", Toast.LENGTH_SHORT).show() }
                    }
                    .setNegativeButton("Copy") { _, _ ->
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Debug Logs", logs)
                        clipboard.setPrimaryClip(clip)
                        context?.let { Toast.makeText(it, "Logs copied to clipboard", Toast.LENGTH_SHORT).show() }
                    }
                    .show()
                true
            }
            
            // Initial local web status
            updateLocalWebStatus(webViewerEnabledPref?.isChecked == true, localWebPortPref?.text ?: "8080")
            
            // Setup remote access status
            remoteStatusPref = findPreference("pref_remote_status")
            stationNamePref = findPreference("pref_station_name")
            enableRemotePref = findPreference("pref_enable_remote")
            
            // Update status when station name changes
            stationNamePref?.setOnPreferenceChangeListener { pref, newValue ->
                pref.summary = newValue.toString()
                updateRemoteStatus(newValue.toString(), enableRemotePref?.isChecked == true)
                true
            }
            
            // Update status when enable switch changes
            enableRemotePref?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                updateRemoteStatus(stationNamePref?.text ?: "", enabled)
                
                // Trigger service update
                val ctx = context ?: return@setOnPreferenceChangeListener true
                val intent = Intent(ctx, AisReceiverService::class.java).apply {
                    putExtra("REMOTE_ENABLED", enabled)
                    putExtra("STATION_NAME", stationNamePref?.text ?: "")
                }
                ctx.startForegroundService(intent)
                
                if (enabled) {
                    Toast.makeText(ctx, "🔄 Initializing Remote Access...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "🛑 Stopping Remote Access...", Toast.LENGTH_SHORT).show()
                }
                
                true
            }
            
            // Handle click on remote status URL
            remoteStatusPref?.setOnPreferenceClickListener {
                val stationName = stationNamePref?.text ?: ""
                val enabled = enableRemotePref?.isChecked == true
                
                if (enabled && stationName.isNotEmpty()) {
                    val sanitizedName = sanitizeStationName(stationName).take(32)
                    // Ensure we use the correct domain here for the link
                    val url = "http://$sanitizedName.connect.porttracker.co"
                    
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    startActivity(intent)
                } else {
                    val ctx = context ?: return@setOnPreferenceClickListener true
                    Toast.makeText(ctx, "Remote access is not enabled", Toast.LENGTH_SHORT).show()
                }
                true
            }
            
            // Initial status update
            updateRemoteStatus(stationNamePref?.text ?: "", enableRemotePref?.isChecked == true)
            
            // TODO: Implement network diagnostics
            // findPreference<Preference>("net_diagnostics")?.setOnPreferenceClickListener { ... }
        }
        
        // setupEditTextPreferenceSummary is now a top-level extension function
        
        private fun updateLocalWebStatus(enabled: Boolean, port: String) {
            if (enabled) {
                val url = "http://localhost:$port"
                val greenText = android.text.SpannableString(url)
                greenText.setSpan(
                    android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#4CAF50")),
                    0, url.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                greenText.setSpan(
                    android.text.style.UnderlineSpan(),
                    0, url.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                localWebStatusPref?.summary = greenText
                localWebStatusPref?.isEnabled = true
            } else {
                val redText = android.text.SpannableString("Not launched")
                redText.setSpan(
                    android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#F44336")),
                    0, redText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                localWebStatusPref?.summary = redText
                localWebStatusPref?.isEnabled = false
            }
        }
        
        private fun updateRemoteStatus(stationName: String, enabled: Boolean) {
            val sanitizedName = sanitizeStationName(stationName).take(32)
            
            if (enabled && sanitizedName.isNotEmpty()) {
                val url = "http://$sanitizedName.connect.porttracker.co"
                val greenText = android.text.SpannableString(url)
                greenText.setSpan(
                    android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#4CAF50")),
                    0, url.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                greenText.setSpan(
                    android.text.style.UnderlineSpan(),
                    0, url.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                remoteStatusPref?.summary = greenText
                remoteStatusPref?.isEnabled = true
            } else {
                val redText = android.text.SpannableString("Not launched")
                redText.setSpan(
                    android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#F44336")),
                    0, redText.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                remoteStatusPref?.summary = redText
                remoteStatusPref?.isEnabled = false
            }
        }
    }

    private companion object {
        const val TAG = "SettingsActivity"
        const val STATUS_REFRESH_INTERVAL = 2000L
        const val CONTROL_REFRESH_INTERVAL = 1000L
        const val SERVICE_STOP_COOLDOWN = 1000L
        const val FORCE_STOP_DELAY = 5000L
        const val SHUTDOWN_DELAY = 500L
        const val AUTO_START_DELAY = 1000L
        const val BOOT_CHECK_DELAY = 3000L
        const val USB_PERMISSION_ACTION = "com.porttracker.ais.USB_PERMISSION"
    }
}
