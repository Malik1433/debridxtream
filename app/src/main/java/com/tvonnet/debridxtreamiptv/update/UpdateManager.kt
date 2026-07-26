package com.tvonnet.debridxtreamiptv.update

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.tvonnet.debridxtreamiptv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app auto-update for sideloaded installs (Fire TV has no store channel).
 *
 * Reads `app_config/version` from Firestore:
 *   { latestVersionCode, latestVersionName, apkUrl, changelog, forceUpdate,
 *     minSupportedVersionCode }
 * If a newer versionCode is published, offers (or forces) an update: downloads the
 * APK from `apkUrl` (Firebase Storage download URL) into the app cache and hands it
 * to the platform package installer via FileProvider. Requires the
 * REQUEST_INSTALL_PACKAGES permission and the user's one-time "install unknown
 * apps" grant (standard for sideloaded TV apps).
 *
 * Owner release flow: build APK → upload to Firebase Storage → set the new
 * versionCode/apkUrl in the admin panel → clients update on next launch.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private var checkedThisProcess = false

    // Long timeouts: APKs are ~35 MB and TV Wi-Fi can be slow.
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    /** Call once per launch from the main screen. No-ops fast when up to date. */
    fun checkOnLaunch(activity: Activity) {
        if (checkedThisProcess) return
        checkedThisProcess = true

        FirebaseFirestore.getInstance().collection("app_config").document("version")
            .get()
            .addOnSuccessListener { snap ->
                if (snap == null || !snap.exists() || activity.isFinishing) return@addOnSuccessListener
                val latest = (snap.getLong("latestVersionCode") ?: 0L).toInt()
                val minSupported = (snap.getLong("minSupportedVersionCode") ?: 0L).toInt()
                val apkUrl = snap.getString("apkUrl").orEmpty()
                if (latest <= BuildConfig.VERSION_CODE || apkUrl.isBlank()) return@addOnSuccessListener

                val forced = (snap.getBoolean("forceUpdate") == true) ||
                    BuildConfig.VERSION_CODE < minSupported
                promptUpdate(
                    activity = activity,
                    versionName = snap.getString("latestVersionName") ?: latest.toString(),
                    changelog = snap.getString("changelog").orEmpty(),
                    apkUrl = apkUrl,
                    forced = forced
                )
            }
            .addOnFailureListener { Log.w(TAG, "update check failed", it) }
    }

    private fun promptUpdate(
        activity: Activity,
        versionName: String,
        changelog: String,
        apkUrl: String,
        forced: Boolean
    ) {
        val message = buildString {
            append("Version $versionName is available.")
            if (changelog.isNotBlank()) append("\n\n").append(changelog)
            if (forced) append("\n\nThis update is required to continue.")
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(message)
            .setCancelable(!forced)
            .setPositiveButton("Update now") { _, _ -> download(activity, apkUrl, forced) }
        if (!forced) dialog.setNegativeButton("Later", null)
        dialog.show()
    }

    private fun download(activity: Activity, apkUrl: String, forced: Boolean) {
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(activity).apply {
            setMessage("Downloading update…")
            setCancelable(false)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apk = File(dir, "app-update.apk")
                if (apk.exists()) apk.delete()

                http.newCall(Request.Builder().url(apkUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("empty body")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        apk.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read: Int
                            var done = 0L
                            while (input.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                                done += read
                                if (total > 0) {
                                    val pct = ((done * 100) / total).toInt()
                                    withContext(Dispatchers.Main) { progress.progress = pct }
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    installApk(activity, apk)
                }
            } catch (e: Exception) {
                Log.e(TAG, "update download failed", e)
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    AlertDialog.Builder(activity)
                        .setTitle("Update failed")
                        .setMessage("Could not download the update. Please try again later.\n(${e.message})")
                        .setCancelable(!forced)
                        .setPositiveButton("Retry") { _, _ -> download(activity, apkUrl, forced) }
                        .apply { if (!forced) setNegativeButton("Later", null) }
                        .show()
                }
            }
        }
    }

    private fun installApk(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(
            activity, "${BuildConfig.APPLICATION_ID}.updates", apk
        )
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
