package com.printbook.app.capture.processing

import android.graphics.Bitmap
import com.printbook.app.capture.analysis.BlackFrameDetector
import com.printbook.app.capture.analysis.FrameSignature
import com.printbook.app.capture.profile.CaptureProfile
import com.printbook.app.capture.profile.CropMath
import com.printbook.app.capture.profile.PixelRect

/** Resultado do processamento de um quadro capturado. */
sealed interface ProcessedFrame {
    data object Black : ProcessedFrame
    data class Page(val bitmap: Bitmap, val signature: FrameSignature, val crop: PixelRect) : ProcessedFrame
}

/**
 * Quadro bruto -> página: detecta tela preta, aplica o recorte do perfil (e o corte de margens,
 * se o perfil pedir) e calcula a assinatura usada no dedupe.
 */
object PageProcessor {

    fun process(frame: Bitmap, profile: CaptureProfile, autoCrop: Boolean): ProcessedFrame {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)

        val blackSample = FrameSignature.sample(w, h, 64, 64) { x, y -> pixels[y * w + x] }
        if (BlackFrameDetector.isBlack(blackSample.luma.toArgb())) return ProcessedFrame.Black

        var rect = PixelRect(0, 0, w, h)
        if (autoCrop) {
            rect = CropMath.profileRect(profile, pixels, w, h)
            if (profile.trimMargins) rect = CropMath.trimUniformMargins(pixels, w, rect)
        }

        val page = if (rect.left == 0 && rect.top == 0 && rect.width == w && rect.height == h) {
            frame
        } else {
            Bitmap.createBitmap(frame, rect.left, rect.top, rect.width, rect.height)
        }
        val signature = FrameSignature.sample(rect.width, rect.height) { x, y ->
            pixels[(rect.top + y) * w + rect.left + x]
        }
        return ProcessedFrame.Page(page, signature, rect)
    }

    private fun ByteArray.toArgb(): IntArray = IntArray(size) {
        val l = this[it].toInt() and 0xFF
        (0xFF shl 24) or (l shl 16) or (l shl 8) or l
    }
}
