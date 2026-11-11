package com.printbook.app.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import java.io.File

/** Gira a página em múltiplos de 90° (rotação guardada no banco, arquivo original intacto). */
class RotateTransformation(private val degrees: Int) : Transformation() {
    override val cacheKey: String = "rotate-$degrees"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (degrees % 360 == 0) return input
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(input, 0, 0, input.width, input.height, matrix, true)
    }
}

/**
 * Pedido de imagem de uma página. [thumbnailPx] > 0 decodifica uma versão reduzida (miniaturas,
 * balão do leitor), muito mais rápida que a página em 1080 px.
 */
@Composable
fun rememberPageRequest(file: File, rotation: Int, thumbnailPx: Int = 0): ImageRequest {
    val context = LocalContext.current
    return remember(file, rotation, thumbnailPx) {
        ImageRequest.Builder(context)
            .data(file)
            .apply { if (thumbnailPx > 0) size(thumbnailPx) }
            .apply { if (rotation % 360 != 0) transformations(RotateTransformation(rotation)) }
            .build()
    }
}
