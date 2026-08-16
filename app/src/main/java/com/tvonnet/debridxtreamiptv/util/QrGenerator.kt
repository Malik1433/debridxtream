package com.tvonnet.debridxtreamiptv.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.util.EnumMap

/**
 * Lightweight QR Code Generator using zxing:core directly.
 * Avoids dependencies like zxing-android-embedded which cause compatibility issues
 * on older Android devices (API 21-23).
 */
object QrGenerator {

    /**
     * Generates a square QR code bitmap.
     * 
     * @param content The string to encode in the QR code.
     * @param size The width/height of the resulting bitmap.
     * @return A Bitmap object or null if generation fails.
     */
    /**
     * @param background what the light modules are painted with. The default is transparent, which
     *   is what the sign-in overlay wants — its code sits on a pale glass panel. **A code on a DARK
     *   surface must pass an opaque light colour**, or the light modules take the surface's colour
     *   and a camera is looking at black on black. That is not a styling preference; it is the
     *   difference between a code that scans and one that cannot.
     * @param quietZoneModules the blank border, in modules. The spec asks for 4; 1 is a deliberate
     *   shortcut that only works when the surrounding surface is already light.
     */
    fun generateQrCode(
        content: String,
        size: Int = 512,
        background: Int = Color.TRANSPARENT,
        quietZoneModules: Int = 1,
    ): Bitmap? {
        return try {
            val hints: MutableMap<EncodeHintType, Any> = EnumMap(EncodeHintType::class.java)
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.MARGIN] = quietZoneModules

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

            val bitmap = Bitmap.createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(toPixels(bitMatrix, background), 0, bitMatrix.width, 0, 0, bitMatrix.width, bitMatrix.height)
            bitmap
        } catch (e: Exception) {
            android.util.Log.e("QrGenerator", "Failed to generate QR code", e)
            null
        }
    }

    // Black pixels for the QR code; the light modules take whatever the caller asked for. The
    // default transparency is what lets the code sit on the glassmorphism panels.
    private fun toPixels(bitMatrix: BitMatrix, background: Int): IntArray {
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else background
            }
        }
        return pixels
    }
}
