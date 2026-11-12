package com.printbook.app.capture.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.printbook.app.capture.analysis.PageIndicator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Lê o indicador de progresso ("13 / 553", "212 páginas restantes · 37%") no rodapé do quadro
 * capturado, com o OCR offline do ML Kit. Não usa a Acessibilidade (que não lê o conteúdo das telas).
 */
class FooterReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Recorta a faixa de baixo do quadro inteiro (onde ficam os rodapés dos leitores). */
    fun footerOf(frame: Bitmap): Bitmap {
        val top = (frame.height * FOOTER_TOP).toInt()
        return Bitmap.createBitmap(frame, 0, top, frame.width, frame.height - top)
    }

    /**
     * @return o indicador lido, ou null se não houver. Recicla [footer] quando o ML Kit termina
     * (mesmo se a corrotina for cancelada antes: o reconhecimento continua lendo a imagem).
     * Não reaproveita o recognize() do OcrWorker por causa dessa reciclagem no fim da tarefa.
     */
    /** Texto do rodapé numa linha só (mesma reciclagem de [read]). */
    suspend fun readText(footer: Bitmap): String {
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(footer, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                .addOnFailureListener { if (cont.isActive) cont.resume("") }
                .addOnCompleteListener { footer.recycle() }
        }
        return text.replace('\n', ' ')
    }

    fun close() = recognizer.close()

    private companion object {
        /** A barra inferior do Minha Biblioteca começa em y≈0,878; Kindle e Play ficam abaixo de 0,95. */
        const val FOOTER_TOP = 0.86f
    }
}
