package com.porttracker.ais

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Handles USB device attach/detach events and manages permissions
 */
class UsbDeviceReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "porttracker-service.USB"
        private const val ACTION_USB_PERMISSION = "com.porttracker.ais.USB_PERMISSION"
        
        // Supported SDR device VID/PID pairs
        private val SUPPORTED_DEVICES = listOf(
            // RTL-SDR dongles (various vendors)
            Pair(0x0bda, 0x2832), // RTL2832U
            Pair(0x0bda, 0x2838), // RTL2838
            Pair(0x0ccd, 0x00a9), // RTL-SDR Blog V3
            Pair(0x0ccd, 0x00b3), // RTL-SDR Blog V4
            Pair(0x1f4d, 0xb803), // Generic RTL-SDR
            Pair(0x1f4d, 0xc803), // Generic RTL-SDR
            // AirSpy
            Pair(0x1d50, 0x60a1), // AirSpy Mini/R2
            // AirSpy HF+
            Pair(0x03eb, 0x800c), // AirSpy HF+
            // HackRF
            Pair(0x1d50, 0x6089), // HackRF
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                
                device?.let { handleDeviceAttached(context, it) }
            }
            
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Log.i(TAG, "USB device detached")
                // Could stop service here if needed
            }
            
            ACTION_USB_PERMISSION -> {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                
                if (granted && device != null) {
                    Log.i(TAG, "USB permission granted for ${device.productName}")
                    startServiceWithDevice(context, device)
                } else {
                    Log.w(TAG, "USB permission denied")
                }
            }
        }
    }

    private fun handleDeviceAttached(context: Context, device: UsbDevice) {
        Log.i(TAG, "USB device attached: ${device.productName} (VID=${device.vendorId}, PID=${device.productId})")
        
        if (!isSupportedDevice(device)) {
            Log.d(TAG, "Device not supported")
            return
        }
        
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val autoStartOnUsb = prefs.getBoolean("auto_start_usb", true)
        
        if (!autoStartOnUsb) {
            Log.d(TAG, "Auto-start on USB disabled")
            return
        }
        
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        
        if (usbManager.hasPermission(device)) {
            startServiceWithDevice(context, device)
        } else {
            Log.i(TAG, "Requesting USB permission...")
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun startServiceWithDevice(context: Context, device: UsbDevice) {
        val deviceType = getDeviceType(device)
        
        Log.i(TAG, "Starting service with device: type=$deviceType, vid=${device.vendorId}, pid=${device.productId}")
        
        // Pass VID/PID - service will open device itself for proper lifecycle management
        val serviceIntent = Intent(context, AisReceiverService::class.java).apply {
            putExtra("USB_VENDOR_ID", device.vendorId)
            putExtra("USB_PRODUCT_ID", device.productId)
            putExtra("SOURCE", deviceType.ordinal)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    private fun isSupportedDevice(device: UsbDevice): Boolean {
        return SUPPORTED_DEVICES.any { it.first == device.vendorId && it.second == device.productId }
    }

    private fun getDeviceType(device: UsbDevice): DeviceType {
        return when {
            device.vendorId == 0x1d50 && device.productId == 0x60a1 -> DeviceType.AIRSPY
            device.vendorId == 0x03eb && device.productId == 0x800c -> DeviceType.AIRSPYHF
            else -> DeviceType.RTLSDR
        }
    }
}

/**
 * Helper class for USB device management
 */
class UsbDeviceManager(private val context: Context) {
    
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
    fun getConnectedDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.filter { device ->
            UsbDeviceReceiver.run {
                // Check if supported
                true // Simplified for now
            }
        }
    }
    
    fun openDevice(device: UsbDevice): Int? {
        if (!usbManager.hasPermission(device)) {
            return null
        }
        return usbManager.openDevice(device)?.fileDescriptor
    }
    
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        // Permission handling logic
    }
}
