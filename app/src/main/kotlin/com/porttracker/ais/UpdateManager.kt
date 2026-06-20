package com.porttracker.ais

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * OTA Update Manager — checks GitHub Releases for new APK versions
 * and downloads/installs them on user request.
 *
 * Flow:
 *  1. checkForUpdate() → calls GitHub Releases API, compares versionCode
 *  2. downloadAndInstall() → downloads APK asset, triggers Android install intent
 *
 * The user must tap "Install" on the device to confirm (Android security requirement).
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"

        // GitHub Releases API endpoint (public repo, no auth needed)
        private const val GITHUB_API_URL =
            "https://api.github.com/repos/maritime-datasystems/porttracker-aiscatcher/releases/latest"

        // Current app version code (from BuildConfig)
        fun getCurrentVersionCode(context: Context): Int {
            return try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get version code", e)
                0
            }
        }

        fun getCurrentVersionName(context: Context): String {
            return try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        }
    }

    // State
    @Volatile var isChecking = false; private set
    @Volatile var isDownloading = false; private set
    @Volatile var downloadProgress = 0; private set
    @Volatile var lastCheckResult: UpdateInfo? = null; private set
    @Volatile var lastError: String? = null; private set

    data class UpdateInfo(
        val available: Boolean,
        val currentVersionCode: Int,
        val currentVersionName: String,
        val latestVersionCode: Int,
        val latestVersionName: String,
        val releaseNotes: String,
        val apkDownloadUrl: String?,
        val apkSizeBytes: Long,
        val publishedAt: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("update_available", available)
            put("current_version_code", currentVersionCode)
            put("current_version_name", currentVersionName)
            put("latest_version_code", latestVersionCode)
            put("latest_version_name", latestVersionName)
            put("release_notes", releaseNotes)
            put("apk_download_url", apkDownloadUrl ?: "")
            put("apk_size_bytes", apkSizeBytes)
            put("published_at", publishedAt)
        }
    }

    /**
     * Check GitHub Releases for a newer version.
     * Runs on calling thread (call from background!).
     */
    fun checkForUpdate(): UpdateInfo {
        isChecking = true
        lastError = null
        try {
            val currentCode = getCurrentVersionCode(context)
            val currentName = getCurrentVersionName(context)

            val conn = URL(GITHUB_API_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "PortTracker-AIS/$currentName")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                throw Exception("GitHub API returned $responseCode")
            }

            val body = conn.inputStream.bufferedReader().readText()
            val release = JSONObject(body)

            val tagName = release.optString("tag_name", "")  // e.g. "v22" or "v3.0.22"
            val releaseName = release.optString("name", tagName)
            val releaseBody = release.optString("body", "")
            val publishedAt = release.optString("published_at", "")

            // Extract version code from tag: "v22" → 22, "v3.0.22" → 22
            val latestCode = tagName.replace(Regex("[^0-9]"), "").toIntOrNull()
                ?: extractVersionCodeFromTag(tagName)

            // Find APK asset
            val assets = release.optJSONArray("assets") ?: JSONArray()
            var apkUrl: String? = null
            var apkSize: Long = 0
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url", null)
                    apkSize = asset.optLong("size", 0)
                    break
                }
            }

            val info = UpdateInfo(
                available = latestCode > currentCode,
                currentVersionCode = currentCode,
                currentVersionName = currentName,
                latestVersionCode = latestCode,
                latestVersionName = releaseName,
                releaseNotes = releaseBody,
                apkDownloadUrl = apkUrl,
                apkSizeBytes = apkSize,
                publishedAt = publishedAt,
            )
            lastCheckResult = info
            Log.i(TAG, "Update check: current=$currentCode, latest=$latestCode, available=${info.available}")
            return info

        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            lastError = e.message ?: "Unknown error"
            val currentCode = getCurrentVersionCode(context)
            val currentName = getCurrentVersionName(context)
            val info = UpdateInfo(
                available = false,
                currentVersionCode = currentCode,
                currentVersionName = currentName,
                latestVersionCode = currentCode,
                latestVersionName = currentName,
                releaseNotes = "",
                apkDownloadUrl = null,
                apkSizeBytes = 0,
                publishedAt = "",
            )
            lastCheckResult = info
            return info
        } finally {
            isChecking = false
        }
    }

    /**
     * Download the APK and trigger Android's package installer.
     * Runs on calling thread (call from background!).
     * Returns true if install intent was launched successfully.
     */
    fun downloadAndInstall(): Boolean {
        val info = lastCheckResult
            ?: throw IllegalStateException("Call checkForUpdate() first")
        val apkUrl = info.apkDownloadUrl
            ?: throw IllegalStateException("No APK download URL in latest release")

        isDownloading = true
        downloadProgress = 0
        lastError = null

        try {
            Log.i(TAG, "Downloading APK from: $apkUrl")

            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "PortTracker-AIS/${info.currentVersionName}")
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true

            // GitHub redirects to S3, follow it
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                throw Exception("Download failed: HTTP $responseCode")
            }

            val totalBytes = conn.contentLength.toLong()
            val apkFile = File(context.getExternalFilesDir(null), "update.apk")

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        bytesRead += count
                        downloadProgress = if (totalBytes > 0) {
                            ((bytesRead * 100) / totalBytes).toInt()
                        } else {
                            -1 // indeterminate
                        }
                    }
                }
            }

            downloadProgress = 100
            Log.i(TAG, "APK downloaded: ${apkFile.length()} bytes → ${apkFile.absolutePath}")

            // Trigger Android install intent
            installApk(apkFile)
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed", e)
            lastError = e.message ?: "Download failed"
            return false
        } finally {
            isDownloading = false
        }
    }

    private fun installApk(apkFile: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7+ requires FileProvider
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        Log.i(TAG, "Launching APK install intent for: $uri")
        context.startActivity(intent)
    }

    fun getStatusJson(): JSONObject = JSONObject().apply {
        put("checking", isChecking)
        put("downloading", isDownloading)
        put("download_progress", downloadProgress)
        put("error", lastError)
        if (lastCheckResult != null) {
            put("update", lastCheckResult!!.toJson())
        }
    }

    private fun extractVersionCodeFromTag(tag: String): Int {
        // Try patterns: "v22", "v3.0.22", "release-22"
        val parts = tag.split(".")
        return parts.lastOrNull()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
    }
}
