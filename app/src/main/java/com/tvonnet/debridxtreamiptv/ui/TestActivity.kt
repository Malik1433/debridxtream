package com.tvonnet.debridxtreamiptv.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "TEST ACTIVITY - IF YOU SEE THIS, LAUNCH WORKS"
        tv.textSize = 32f
        tv.setTextColor(android.graphics.Color.WHITE)
        tv.setBackgroundColor(android.graphics.Color.BLUE)
        setContentView(tv)
    }
}
