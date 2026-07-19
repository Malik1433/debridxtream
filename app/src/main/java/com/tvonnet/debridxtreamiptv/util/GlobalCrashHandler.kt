package com.tvonnet.debridxtreamiptv.util

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.tvonnet.debridxtreamiptv.ui.MainActivity
import kotlin.system.exitProcess

class GlobalCrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    internal var exitLogic: () -> Unit = {
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(1)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // LP-D-5: never log the raw message/trace — media3 HTTP exceptions embed the
        // credentialed stream URL. Class name + crash site only.
        Log.e(
            "GlobalCrashHandler",
            "CRASH DETECTED: ${throwable::class.java.name} @ ${throwable.stackTrace.firstOrNull()}"
        )

        // LP-D-1: this handler restarts the app instead of chaining to the system
        // handler, so Crashlytics' own fatal pipeline never runs. Persist the crash
        // explicitly (uploaded on next launch) so field crashes are visible remotely.
        try {
            val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("fatal_restart", true)
            crashlytics.recordException(throwable)
        } catch (_: Exception) {
            // Crashlytics unavailable (e.g. not initialized) — never block the restart.
        }

        try {
            val prefs = context.getSharedPreferences("app_stability", Context.MODE_PRIVATE)
            val count = prefs.getInt("startup_crash_count", 0) + 1
            prefs.edit().putInt("startup_crash_count", count).apply()

            if (count >= 3) {
                // Start RecoveryActivity
                val intent = Intent(context, com.tvonnet.debridxtreamiptv.ui.recovery.RecoveryActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(intent)
            } else {
                // Normal restart to MainActivity
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(intent)
            }

            // Kill current process via exitLogic
            exitLogic()

        } catch (e: Exception) {
            // Fallback to default if restart fails
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(context))
        }
    }
}
