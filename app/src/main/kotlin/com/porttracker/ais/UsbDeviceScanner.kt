package com.porttracker.ais

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Scans for connected USB SDR devices.
 * Device support checks are delegated to [SupportedDevices].
 */
object UsbDeviceScanner {
    private const val TAG = "porttracker-service.Scanner"
    
    data class SdrDeviceInfo(
        val found: Boolean,
        val deviceName: String,
        val manufacturer: String,
        val productId: Int,
        val vendorId: Int,
        val deviceType: String,
        val isUsable: Boolean,
        val usbDevice: UsbDevice? = null
    )
    
    /**
     * Check if a vendor/product ID pair is a supported SDR device
     */
    fun isSupportedDevice(vendorId: Int, productId: Int): Boolean =
        SupportedDevices.isSupportedDevice(vendorId, productId)
    
    /**
     * Scan for connected SDR devices
     */
    fun scanForDevices(context: Context): SdrDeviceInfo {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList
            
            Log.d(TAG, "Scanning ${deviceList.size} USB devices")
            
            for ((_, device) in deviceList) {
                val vendorId = device.vendorId
                val productId = device.productId
                
                val sdrDevice = SupportedDevices.findDevice(vendorId, productId) ?: continue
                val hasPermission = usbManager.hasPermission(device)
                return SdrDeviceInfo(
                    found = true,
                    deviceName = device.productName ?: sdrDevice.label,
                    manufacturer = device.manufacturerName ?: "Unknown",
                    productId = productId,
                    vendorId = vendorId,
                    deviceType = sdrDevice.label,
                    isUsable = hasPermission,
                    usbDevice = device
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning USB devices", e)
        }
        
        return SdrDeviceInfo(
            found = false,
            deviceName = "No device found",
            manufacturer = "",
            productId = 0,
            vendorId = 0,
            deviceType = "",
            isUsable = false
        )
    }
    
    /**
     * Get status text for display
     */
    fun getStatusText(info: SdrDeviceInfo): String {
        return when {
            !info.found -> "No SDR device connected"
            !info.isUsable -> "${info.deviceName} (Permission needed)"
            else -> "${info.deviceName} (Ready)"
        }
    }
    
    /**
     * Get all connected USB devices with SDR devices highlighted
     */
    fun getAllUsbDevices(context: Context): String {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList
            
            if (deviceList.isEmpty()) {
                return "No USB devices connected"
            }
            
            val lines = mutableListOf<String>()
            for ((_, device) in deviceList) {
                val vendorId = device.vendorId
                val productId = device.productId
                val name = device.productName ?: "Unknown Device"
                
                val isSdr = SupportedDevices.isSupportedDevice(vendorId, productId)
                
                val prefix = if (isSdr) "📡 " else "   "
                val suffix = if (isSdr) " [SDR]" else ""
                lines.add("$prefix$name (${String.format("%04X", vendorId)}:${String.format("%04X", productId)})$suffix")
            }
            
            return lines.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error listing USB devices", e)
            return "Error scanning USB"
        }
    }
}
