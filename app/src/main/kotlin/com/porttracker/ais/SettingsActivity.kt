package com.porttracker.ais

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2

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
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            checkAndRequestUsbPermission()
        }, 1000)

        // Auto-start service on app launch if enabled (with delay for USB enumeration)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val autoStartOnLaunch = prefs.getBoolean("auto_start_launch", true)
        
        if (autoStartOnLaunch && !AisReceiverService.isRunning) {
            // Delay 3 seconds to allow USB device enumeration and permission
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!AisReceiverService.isRunning) {
                    // Only start if SDR is connected and has permission
                    val deviceInfo = UsbDeviceScanner.scanForDevices(this)
                    if (deviceInfo.found && deviceInfo.isUsable) {
                        val intent = Intent(this, AisReceiverService::class.java).apply {
                            putExtra("USB_VENDOR_ID", deviceInfo.vendorId)
                            putExtra("USB_PRODUCT_ID", deviceInfo.productId)
                        }
                        startForegroundService(intent)
                    }
                }
            }, 3000)
        }
    }
    
    private fun checkAndRequestUsbPermission() {
        try {
            val usbManager = getSystemService(android.content.Context.USB_SERVICE) as? android.hardware.usb.UsbManager ?: return
            val deviceInfo = UsbDeviceScanner.scanForDevices(this)
            
            if (deviceInfo.found && !deviceInfo.isUsable) {
                // SDR found but no permission - request it
                for ((_, device) in usbManager.deviceList) {
                    if (device.vendorId == deviceInfo.vendorId && device.productId == deviceInfo.productId) {
                        val pendingIntent = android.app.PendingIntent.getBroadcast(
                            this, 0,
                            Intent("com.porttracker.ais.USB_PERMISSION"),
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
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
                val usbManager = ctx.getSystemService(android.content.Context.USB_SERVICE) as? android.hardware.usb.UsbManager
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
                        val pendingIntent = android.app.PendingIntent.getBroadcast(
                            ctx, 0,
                            Intent("com.porttracker.ais.USB_PERMISSION"),
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
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
                        handler.postDelayed(this, 2000)
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
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
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
        }

        private fun setupEditTextPreferenceSummary(key: String) {
            findPreference<androidx.preference.EditTextPreference>(key)?.apply {
                summary = text ?: ""
                setOnPreferenceChangeListener { pref, newValue ->
                    pref.summary = newValue.toString()
                    true
                }
            }
        }

        private fun setupListPreferenceSummary(key: String) {
            findPreference<androidx.preference.ListPreference>(key)?.apply {
                summary = entry ?: ""
                setOnPreferenceChangeListener { pref, newValue ->
                    val listPref = pref as androidx.preference.ListPreference
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
            findPreference<androidx.preference.SwitchPreferenceCompat>("service_enabled")?.apply {
                isChecked = AisReceiverService.isRunning
                setOnPreferenceChangeListener { _, newValue ->
                    // Set debounce - don't let status updates override for 3 seconds
                    userInteractingUntil = System.currentTimeMillis() + 3000
                    
                    if (newValue as Boolean) {
                        startService()
                    } else {
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
                        handler.postDelayed(this, 1000)
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
                findPreference<androidx.preference.SwitchPreferenceCompat>("service_enabled")?.isChecked = 
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
                Toast.makeText(ctx, "❌ No SDR device connected", Toast.LENGTH_SHORT).show()
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
        
        private fun requestSdrPermission(ctx: android.content.Context, deviceInfo: UsbDeviceScanner.SdrDeviceInfo) {
            try {
                val usbManager = ctx.getSystemService(android.content.Context.USB_SERVICE) as? android.hardware.usb.UsbManager ?: return
                for ((_, device) in usbManager.deviceList) {
                    if (device.vendorId == deviceInfo.vendorId && device.productId == deviceInfo.productId) {
                        val pendingIntent = android.app.PendingIntent.getBroadcast(
                            ctx, 0,
                            Intent("com.porttracker.ais.USB_PERMISSION"),
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
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
            handler.postDelayed({ isStoppingService = false }, 1000)
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
            handler.postDelayed({ isStoppingService = false }, 5000)
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
            }, 500)
        }
    }

    // =============== WEB FRAGMENT ===============
    class NetworkingFragment : PreferenceFragmentCompat() {
        private var remoteStatusPref: Preference? = null
        private var stationNamePref: androidx.preference.EditTextPreference? = null
        private var enableRemotePref: androidx.preference.SwitchPreferenceCompat? = null
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_networking, rootKey)

            setupEditTextPreferenceSummary("dns_primary")
            setupEditTextPreferenceSummary("dns_secondary")
            setupEditTextPreferenceSummary("pref_porttracker_username")
            setupEditTextPreferenceSummary("pref_station_name")
            setupEditTextPreferenceSummary("pref_local_web_port")
            
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
                true
            }
            
            // Handle click on remote status URL
            remoteStatusPref?.setOnPreferenceClickListener {
                val stationName = stationNamePref?.text ?: ""
                val enabled = enableRemotePref?.isChecked == true
                
                if (enabled && stationName.isNotEmpty()) {
                    val sanitizedName = stationName.lowercase()
                        .replace(Regex("[^a-z0-9-]"), "-")
                        .replace(Regex("-+"), "-")
                        .trim('-')
                        .take(32)
                    val url = "http://$sanitizedName.porttracker.co"
                    
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    startActivity(intent)
                } else {
                    Toast.makeText(context, "Remote access is not enabled", Toast.LENGTH_SHORT).show()
                }
                true
            }
            
            // Initial status update
            updateRemoteStatus(stationNamePref?.text ?: "", enableRemotePref?.isChecked == true)
            
            findPreference<Preference>("net_diagnostics")?.setOnPreferenceClickListener {
                Toast.makeText(context, "Networking diagnostics starting...", Toast.LENGTH_SHORT).show()
                // Implementation for diagnostics can be added here
                true
            }
        }
        
        private fun updateRemoteStatus(stationName: String, enabled: Boolean) {
            val sanitizedName = stationName.lowercase()
                .replace(Regex("[^a-z0-9-]"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(32)
            
            if (enabled && sanitizedName.isNotEmpty()) {
                val url = "http://$sanitizedName.porttracker.co"
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

        private fun setupEditTextPreferenceSummary(key: String) {
            findPreference<androidx.preference.EditTextPreference>(key)?.apply {
                summary = text ?: ""
                setOnPreferenceChangeListener { pref, newValue ->
                    pref.summary = newValue.toString()
                    true
                }
            }
        }
    }
}
