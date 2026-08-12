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
        // LP-D-5: never log the MESSAGE — media3 HTTP exceptions embed the credentialed stream
        // URL. The frames themselves carry no such thing (class, method, line only), and one
        // frame proved to be too few: a field crash reported IllegalStateException at
        // FragmentManagerImpl and there was no way to tell which of our screens committed the
        // transaction. Log a short frame window so the next occurrence is diagnosable from the
        // device, without waiting on a Crashlytics round-trip.
        Log.e(
            "GlobalCrashHandler",
            buildString {
                append("CRASH DETECTED: ").append(throwable::class.java.name)
                throwable.stackTrace.take(CRASH_FRAMES).forEach { append("\n    at ").append(it) }
                throwable.cause?.let { cause ->
                    append("\n  caused by: ").append(cause::class.java.name)
                    cause.stackTrace.take(CRASH_FRAMES).forEach { append("\n    at ").append(it) }
                }
            }
        )

        // LP-D-1: this handler restarts the app instead of chaining to the system
        // handler, so Crashlytics' own fatal pipeline never runs. Persist the crash
        // explicitly (uploaded on next launch) so field crashes are visible remotely.
        try {
            val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            crashlytics.setCustomKey("fatal_restart", true)
            crashlytics.recordException(throwable)
            // QA fix: recordException persists on a background executor; killProcess
            // right after would intermittently lose the report. A short bounded wait
            // lets the disk write land — worst case adds 500ms to the crash-restart.
            Thread.sleep(500)
        } catch (_: Exception) {
            // Crashlytics unavailable (e.g. not initialized) — never block the restart.
        }

        try {
            val prefs = context.getSharedPreferences("app_stability", Context.MODE_PRIVATE)
            val count = prefs.getInt("startup_crash_count", 0) + 1
            // commit() (not apply()) so the incremented count is durably persisted BEFORE the
            // killProcess below — apply()'s async write was intermittently lost on crash-restart,
            // defeating the 3-strikes RecoveryActivity trigger.
            prefs.edit().putInt("startup_crash_count", count).commit()

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
            // Fallback to default if restart fails. Logged first — losing the reason the crash
            // handler itself failed makes crash reports impossible to interpret.
            android.util.Log.e("GlobalCrashHandler", "Crash-restart path failed", e)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        /** Enough frames to name the caller across a framework wrapper, short enough to stay
         *  readable in a logcat line and to leak nothing beyond our own class names. */
        private const val CRASH_FRAMES = 8

        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(context))
        }
    }
}
