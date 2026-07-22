package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi

/**
 * Picture-in-Picture entry (Phase P9): the platform capability check and the
 * PictureInPictureParams build/enter call.
 *
 * Deliberately narrow — `hideUiForPiP` / `showUiForNormalMode` stay on
 * [PlayerActivity] because they orchestrate eight of the screen's own collaborators
 * (controller, episode browser, X-Ray, EPG/VOD overlays, live OSD, action bar,
 * history). Dragging those in here would move the coupling, not remove it.
 */
internal class PlayerPipController(private val activity: Activity) {

    fun isSupported(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) ||
                activity.packageManager.hasSystemFeature("android.software.picture_in_picture")
        } else {
            false
        }

    /**
     * Enters PiP sized to [sourceWidth] x [sourceHeight].
     * Returns true only when the system accepted the transition; the caller decides
     * what to hide on success and what to show on failure.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun enter(sourceWidth: Int, sourceHeight: Int): Boolean {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setSourceRectHint(Rect(0, 0, sourceWidth, sourceHeight))
            .build()
        return activity.enterPictureInPictureMode(params)
    }
}
