package com.printbook.app.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Pedido de miniatura + o que chamar quando ela carregar (ver [rememberSmoothPageRequest]). */
class SmoothPageRequest(val request: ImageRequest, val onLoaded: () -> Unit)

/**
 * Miniatura que troca rápido (balão do leitor, última captura no Turbo): enquanto a nova página
 * decodifica, mostra a última que já carregou, então a imagem nunca fica vazia.
 */
@Composable
fun rememberSmoothPageRequest(file: File, rotation: Int, thumbnailPx: Int, cacheTag: String): SmoothPageRequest {
    val context = LocalContext.current
    var lastLoaded by remember { mutableStateOf<String?>(null) }
    val key = "$cacheTag-${file.name}-$rotation-$thumbnailPx"
    return remember(key) {
        val request = ImageRequest.Builder(context)
            .data(file)
            .size(thumbnailPx)
            .memoryCacheKey(key)
            .placeholderMemoryCacheKey(lastLoaded)
            .apply { if (rotation % 360 != 0) transformations(RotateTransformation(rotation)) }
            .build()
        SmoothPageRequest(request) { lastLoaded = key }
    }
}
