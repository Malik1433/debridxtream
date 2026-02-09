package com.tvonnet.debridxtreamiptv.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
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
    fun generateQrCode(content: String, size: Int = 512): Bitmap? {
        return try {
            val hints: MutableMap<EncodeHintType, Any> = EnumMap(EncodeHintType::class.java)
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.MARGIN] = 1 // Small margin for premium look

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    // Black pixels for the QR code, transparent or white for background
                    // Using deep black and transparent for better glassmorphism integration
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.TRANSPARENT
                }
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            android.util.Log.e("QrGenerator", "Failed to generate QR code", e)
            null
        }
    }
}
