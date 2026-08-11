package com.tvonnet.debridxtreamiptv.ui.recovery

import com.tvonnet.debridxtreamiptv.R

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.tvonnet.debridxtreamiptv.ui.MainActivity
import java.io.File
import kotlin.system.exitProcess
import com.tvonnet.debridxtreamiptv.util.lockLandscapeOnTouchDevices

class RecoveryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // M13: one orientation for the whole app on a phone — landscape, like the TV.
        lockLandscapeOnTouchDevices()
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1a1a1a"))
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = context.getString(R.string.c_app_recovery_mode)
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val message = TextView(this).apply {
            text = context.getString(R.string.c_debridxtream_detected_consecutive_crashes)
            textSize = 18f
            setTextColor(Color.parseColor("#cccccc"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }

        val btnReset = Button(this).apply {
            text = context.getString(R.string.c_clear_cache_reset_settings)
            textSize = 16f
            setBackgroundColor(Color.parseColor("#ff4444"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                clearAppData()
                restartApp()
            }
        }

        val btnRestart = Button(this).apply {
            text = context.getString(R.string.c_try_restarting_normally)
            textSize = 16f
            setBackgroundColor(Color.parseColor("#4444ff"))
            setTextColor(Color.WHITE)
            setPadding(0, 32, 0, 0)
            setOnClickListener {
                restartApp()
            }
        }

        layout.addView(title)
        layout.addView(message)
        layout.addView(btnReset)
        
        // Add margin between buttons
        val space = android.widget.Space(this)
        space.layoutParams = LinearLayout.LayoutParams(1, 32)
        layout.addView(space)
        
        layout.addView(btnRestart)

        setContentView(layout)
    }

    private fun clearAppData() {
        try {
            // Clear SharedPreferences
            val sharedPrefsDir = File(applicationInfo.dataDir, "shared_prefs")
            if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory) {
                sharedPrefsDir.listFiles()?.forEach { it.delete() }
            }
            
            // Clear Databases
            val dbsDir = File(applicationInfo.dataDir, "databases")
            if (dbsDir.exists() && dbsDir.isDirectory) {
                dbsDir.listFiles()?.forEach { it.delete() }
            }
            
            // Reset crash counter explicitly to be safe
            val prefs = getSharedPreferences("app_stability", Context.MODE_PRIVATE)
            prefs.edit().putInt("startup_crash_count", 0).apply()
        } catch (e: Exception) {
            android.util.Log.e("RecoveryActivity", "Recovery cleanup failed", e)
        }
    }

    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
        exitProcess(0)
    }
}
