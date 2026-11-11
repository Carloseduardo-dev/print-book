package com.printbook.app.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.printbook.app.data.db.dao.PageDao
import com.printbook.app.data.files.PageFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Uma página do PDF por imagem, no tamanho proporcional à página capturada (respeita a rotação). */
@Singleton
class PdfBookExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pageDao: PageDao,
    private val files: PageFileStore,
) : BookExporter {
    override val available: Boolean = true

    override suspend fun exportPdf(bookId: Long, target: Uri, onProgress: (Int, Int) -> Unit): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pages = pageDao.getForBook(bookId)
                val doc = PdfDocument()
                try {
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    pages.forEachIndexed { i, page ->
                        val bmp = BitmapFactory.decodeFile(files.file(bookId, page.fileName).absolutePath)
                            ?: return@forEachIndexed
                        val rotated = page.rotation % 180 != 0
                        val w = if (rotated) bmp.height else bmp.width
                        val h = if (rotated) bmp.width else bmp.height
                        // 2 px da imagem = 1 pt no PDF (a imagem é embutida na resolução original).
                        val pw = (w / 2).coerceAtLeast(1)
                        val ph = (h / 2).coerceAtLeast(1)
                        val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                        val m = Matrix().apply {
                            postRotate(page.rotation.toFloat(), bmp.width / 2f, bmp.height / 2f)
                            if (rotated) postTranslate((bmp.height - bmp.width) / 2f, (bmp.width - bmp.height) / 2f)
                            postScale(pw.toFloat() / w, ph.toFloat() / h)
                        }
                        pdfPage.canvas.drawBitmap(bmp, m, paint)
                        doc.finishPage(pdfPage)
                        bmp.recycle()
                        withContext(Dispatchers.Main) { onProgress(i + 1, pages.size) }
                    }
                    context.contentResolver.openOutputStream(target)?.use { doc.writeTo(it) }
                        ?: error("Não foi possível abrir o arquivo de destino")
                    pages.size
                } finally {
                    doc.close()
                }
            }
        }
}
