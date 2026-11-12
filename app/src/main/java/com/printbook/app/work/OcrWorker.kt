package com.printbook.app.work

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.printbook.app.data.db.dao.PageDao
import com.printbook.app.data.db.dao.PageTextDao
import com.printbook.app.data.db.entity.OcrState
import com.printbook.app.data.db.entity.PageTextEntity
import com.printbook.app.data.files.PageFileStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reconhece o texto (ML Kit, modelo embutido, offline) das páginas PENDING de um livro e grava no
 * índice de busca. Processa em lotes até não sobrar nenhuma; pode ser interrompido e retomado.
 */
@HiltWorker
class OcrWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: PageTextDao,
    private val pageDao: PageDao,
    private val files: PageFileStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK_ID, -1)
        if (bookId < 0) return Result.failure()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var processed = 0
        var totalMs = 0L
        try {
            while (!isStopped) {
                val batch = dao.pendingPages(bookId, BATCH)
                if (batch.isEmpty()) break
                for (page in batch) {
                    if (isStopped) break
                    val start = System.nanoTime()
                    // O arquivo pode ter sido trocado no meio do lote (compactar livro): relê o nome atual.
                    val bitmap = BitmapFactory.decodeFile(files.file(bookId, page.fileName).absolutePath)
                        ?: pageDao.getByIds(listOf(page.id)).firstOrNull()?.let {
                            BitmapFactory.decodeFile(files.file(bookId, it.fileName).absolutePath)
                        }
                    if (bitmap == null) {
                        dao.setState(page.id, OcrState.FAILED)
                        continue
                    }
                    try {
                        val text = recognizer.recognize(InputImage.fromBitmap(bitmap, page.rotation))
                        dao.upsert(PageTextEntity(pageId = page.id, bookId = bookId, text = text))
                        dao.setState(page.id, OcrState.DONE)
                    } catch (e: Exception) {
                        Log.w(TAG, "OCR falhou na página ${page.id}", e)
                        dao.setState(page.id, OcrState.FAILED)
                    } finally {
                        bitmap.recycle()
                    }
                    processed++
                    totalMs += (System.nanoTime() - start) / 1_000_000
                    setProgress(workDataOf(KEY_PROCESSED to processed))
                }
            }
        } finally {
            recognizer.close()
        }
        if (processed > 0) Log.i(TAG, "Livro $bookId: OCR de $processed páginas, média ${totalMs / processed} ms/página")
        return Result.success()
    }

    private suspend fun com.google.mlkit.vision.text.TextRecognizer.recognize(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            process(image)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_PROCESSED = "processed"
        private const val TAG = "OcrWorker"
        private const val BATCH = 20
    }
}
