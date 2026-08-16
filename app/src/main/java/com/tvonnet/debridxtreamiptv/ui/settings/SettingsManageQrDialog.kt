package com.tvonnet.debridxtreamiptv.ui.settings

import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.licensing.LicenseManager
import com.tvonnet.debridxtreamiptv.util.QrGenerator

/**
 * "Manage on your phone" — the code that opens THIS device's page on the customer's phone (D4).
 *
 * It exists because of what setting a device up actually costs today: a debrid add-on link is a
 * hundred characters of `https://…/manifest.json`, and the only way in was to type it on a
 * television with a remote control. Scanning a square and typing on a phone is the whole point.
 *
 * The URL carries the device's activation code, not a token: it identifies WHICH device the page
 * should show, and grants nothing. Anyone who photographs the screen still has to be signed into
 * the account to see or change anything — which is the owner's decision, and the right one, because
 * this page reaches IPTV credentials.
 *
 * Built in code rather than as a layout because there is nothing to fork: one square, one line of
 * text, identical on both form factors. What is NOT identical is how it is dismissed, and the
 * dialog handles both — BACK on a television, the scrim on a phone.
 */
object SettingsManageQrDialog {

    /**
     * @param host MUST be the Activity. A dialog is a window, and only an Activity context carries
     *   the token to add one — building the Builder from a `createConfigurationContext` wrapper
     *   throws `BadTokenException: token null is not valid`, which is exactly what the first
     *   attempt at the un-scaling below did.
     */
    fun show(host: Context) {
        val code = runCatching { LicenseManager.getInstance(host).activationCode }.getOrDefault("")
        val url = manageUrl(host, code)
        // The 1.6x app scale belongs to the screens still wearing the 10-foot layout. Inside a
        // dialog it turns three lines of explanation into six and pushes the code towards the
        // bottom of the screen, so the CONTENT is built unscaled — the dialog itself still comes
        // from the Activity, because that is where the window token lives.
        val context = com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi.unscaledContext(host)

        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val qrSize = (220 * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }

        // The explanation is a view of ours rather than the dialog's `setMessage`, so that it is
        // unscaled along with everything else here.
        content.addView(
            TextView(context).apply {
                setText(R.string.s_manage_on_your_phone_body)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (18 * density).toInt())
            }
        )

        // WHITE, with the full four-module quiet zone: this dialog is dark, and a code whose light
        // modules take the dialog's colour is black on black — it renders, it looks like a QR, and
        // no camera on earth can read it. Caught on the emulator doing exactly that.
        QrGenerator.generateQrCode(url, qrSize, background = android.graphics.Color.WHITE, quietZoneModules = 4)?.let { bitmap ->
            content.addView(
                ImageView(context).apply {
                    setImageBitmap(bitmap)
                    // The QR IS the instruction, and the sentence above already gives it — so the
                    // image is decorative to a screen reader rather than a second, useless label.
                    contentDescription = null
                    layoutParams = LinearLayout.LayoutParams(qrSize, qrSize)
                }
            )
        }

        content.addView(
            TextView(context).apply {
                // The address in full, because a QR is useless to somebody whose camera will not
                // focus, or who is reading this on the device they wanted to scan WITH.
                text = url
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, (14 * density).toInt(), 0, 0)
                alpha = 0.7f
            }
        )

        AlertDialog.Builder(host)
            .setTitle(R.string.s_manage_on_your_phone)
            .setView(content)
            .setPositiveButton(R.string.phone_close, null)
            .show()
    }

    /**
     * `…/account/manage?device=CODE`.
     *
     * The base is the same string the sign-in QR uses, with its `/link` path swapped — one place
     * decides where this product's account lives, so a domain change cannot leave one of the two
     * codes pointing at the old one.
     */
    fun manageUrl(context: Context, code: String): String {
        val base = context.getString(R.string.companion_setup_url).trimEnd('/').removeSuffix("/link")
        val manage = "$base/account/manage"
        return if (code.isBlank()) manage else "$manage?device=$code"
    }
}
