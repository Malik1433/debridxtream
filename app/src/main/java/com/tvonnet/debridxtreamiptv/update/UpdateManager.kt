package com.tvonnet.debridxtreamiptv.update

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R
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

    /** A downloaded APK waiting only on the user granting the install permission. */
    private var pendingApk: File? = null

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
        fetchAndOffer(activity, announceResult = false)
    }

    /**
     * Settings → About → "Check for Update". Deliberately IGNORES [checkedThisProcess]: that guard
     * exists so the launch check runs once per process, and reusing it here would make the button
     * do nothing on the second press — a new bug wearing the old one's clothes.
     *
     * It also always ANSWERS. A silent check is what hid the original problem: the config carried
     * `latestVersionCode = 2` against an app on 37 (37 had been typed into the versionName box),
     * so the app correctly decided there was nothing to offer and said nothing at all, and it read
     * as "update is broken". On demand, "you are on the latest build" is a result too.
     */
    fun checkNow(activity: Activity) {
        Toast.makeText(activity, activity.getString(R.string.update_checking), Toast.LENGTH_SHORT).show()
        fetchAndOffer(activity, announceResult = true)
    }

    private fun fetchAndOffer(activity: Activity, announceResult: Boolean) {
        FirebaseFirestore.getInstance().collection("app_config").document("version")
            .get()
            .addOnSuccessListener { snap ->
                if (activity.isFinishing) return@addOnSuccessListener
                if (snap == null || !snap.exists()) {
                    if (announceResult) toast(activity, R.string.update_none_configured)
                    return@addOnSuccessListener
                }
                val latest = (snap.getLong("latestVersionCode") ?: 0L).toInt()
                val minSupported = (snap.getLong("minSupportedVersionCode") ?: 0L).toInt()
                val apkUrl = snap.getString("apkUrl").orEmpty()
                if (latest <= BuildConfig.VERSION_CODE || apkUrl.isBlank()) {
                    if (announceResult) toast(activity, R.string.update_up_to_date)
                    return@addOnSuccessListener
                }

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
            .addOnFailureListener {
                Log.w(TAG, "update check failed", it)
                if (announceResult && !activity.isFinishing) toast(activity, R.string.update_check_failed)
            }
    }

    private fun toast(activity: Activity, resId: Int) =
        Toast.makeText(activity, activity.getString(resId), Toast.LENGTH_LONG).show()

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
            .setTitle(R.string.c_update_available)
            .setMessage(message)
            .setCancelable(!forced)
            .setPositiveButton("Update now") { _, _ -> download(activity, apkUrl, forced) }
        if (!forced) dialog.setNegativeButton("Later", null)
        dialog.show()
    }

    private fun download(activity: Activity, apkUrl: String, forced: Boolean) {
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(activity).apply {
            setMessage(activity.getString(R.string.c_downloading_update))
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

                streamApkTo(apk, apkUrl, progress)

                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    installApk(activity, apk)
                }
            } catch (e: Exception) {
                Log.e(TAG, "update download failed", e)
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    showDownloadFailedDialog(activity, apkUrl, forced, e)
                }
            }
        }
    }

    // Streams the APK to disk in 64K chunks, publishing percent progress to the dialog.
    @Suppress("DEPRECATION")
    private suspend fun streamApkTo(apk: File, apkUrl: String, progress: ProgressDialog) {
        http.newCall(Request.Builder().url(apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty body")
            copyBodyToFile(body, apk, progress)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun copyBodyToFile(body: okhttp3.ResponseBody, apk: File, progress: ProgressDialog) {
        val total = body.contentLength()
        body.byteStream().use { input ->
            apk.outputStream().use { out -> copyLoop(input, out, total, progress) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun copyLoop(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        total: Long,
        progress: ProgressDialog
    ) {
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

    // A forced update may not be dismissed — retry is the only way forward, as before.
    private fun showDownloadFailedDialog(activity: Activity, apkUrl: String, forced: Boolean, e: Exception) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.c_update_failed)
            .setMessage("Could not download the update. Please try again later.\n(${e.message})")
            .setCancelable(!forced)
            .setPositiveButton("Retry") { _, _ -> download(activity, apkUrl, forced) }
            .apply { if (!forced) setNegativeButton("Later", null) }
            .show()
    }

    private fun installApk(activity: Activity, apk: File) {
        if (!canInstallPackages(activity)) {
            promptForInstallPermission(activity, apk)
            return
        }
        launchPackageInstaller(activity, apk)
    }

    /**
     * Found on the Fire TV, and it defeated the whole feature: the download finished, the installer
     * opened, and the system answered "your TV is not allowed to install unknown apps from this
     * source" — a dead end with no way forward from inside the app. Every sideloaded install hits
     * this until the user grants the per-app permission ONCE, so the app has to ask for it and
     * take them there rather than hand the user to a wall.
     */
    private fun canInstallPackages(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()

    private fun promptForInstallPermission(activity: Activity, apk: File) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setPositiveButton(R.string.update_permission_open_settings) { _, _ ->
                // The APK is already downloaded and stays in the cache, so returning here after
                // granting the permission resumes at the install step rather than re-downloading.
                pendingApk = apk
                openUnknownSourcesSettings(activity)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openUnknownSourcesSettings(activity: Activity) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${BuildConfig.APPLICATION_ID}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            // Some TV builds ship no such settings screen; the generic one is better than nothing.
            Log.w(TAG, "unknown-sources settings unavailable", e)
            runCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    /**
     * Call when the app comes back to the foreground: if the user has just granted the permission,
     * the already-downloaded APK is installed without making them start the update again.
     */
    fun resumePendingInstall(activity: Activity) {
        val apk = pendingApk ?: return
        if (!apk.exists() || !canInstallPackages(activity)) return
        pendingApk = null
        launchPackageInstaller(activity, apk)
    }

    private fun launchPackageInstaller(activity: Activity, apk: File) {
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
