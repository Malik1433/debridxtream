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

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e("GlobalCrashHandler", "CRASH DETECTED: ${throwable.message}", throwable)

        try {
            // Show toast on UI thread
            Thread {
                Looper.prepare()
                Toast.makeText(context, "App crashed. Restarting to recover...", Toast.LENGTH_LONG).show()
                Looper.loop()
            }.start()
            
            Thread.sleep(1000)

            // Restart app
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)

            // Kill current process
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(1)

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
