package com.printbook.app.data.repository

import android.graphics.Bitmap
import android.net.Uri
import com.printbook.app.data.db.dao.BookDao
import com.printbook.app.data.db.dao.BookWithCover
import com.printbook.app.data.db.dao.BookmarkDao
import com.printbook.app.data.db.dao.CollectionDao
import com.printbook.app.data.db.dao.EditorTransactions
import com.printbook.app.data.db.dao.OcrProgress
import com.printbook.app.data.db.dao.PageDao
import com.printbook.app.data.db.dao.PageTextDao
import com.printbook.app.data.db.dao.TextSearchHit
import com.printbook.app.data.search.TextTools
import com.printbook.app.work.WorkScheduler
import com.printbook.app.data.db.entity.BookCollectionCrossRef
import com.printbook.app.data.db.entity.BookEntity
import com.printbook.app.data.db.entity.BookStatus
import com.printbook.app.data.db.entity.BookmarkEntity
import com.printbook.app.data.db.entity.CollectionEntity
import com.printbook.app.data.db.entity.PageEntity
import com.printbook.app.data.db.entity.SourceApp
import com.printbook.app.data.files.PageFileStore
import com.printbook.app.data.files.WebpFormat
import com.printbook.app.data.prefs.PageQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val pageDao: PageDao,
    private val collectionDao: CollectionDao,
    private val bookmarkDao: BookmarkDao,
    private val editor: EditorTransactions,
    private val files: PageFileStore,
    private val pageText: PageTextDao,
    private val work: WorkScheduler,
) {
    /** Serializa a inserção de páginas para manter as posições consecutivas. */
    private val insertLock = Mutex()

    // ---- Leitura ----
    fun observeLibrary(): Flow<List<BookWithCover>> = bookDao.observeAllWithCover()
    fun observeRecentlyRead(limit: Int = 10) = bookDao.observeRecentlyRead(limit)
    fun observeRecentlyAdded(limit: Int = 10) = bookDao.observeRecentlyAdded(limit)
    fun observeInCollection(collectionId: Long) = bookDao.observeInCollection(collectionId)
    fun observeBook(id: Long): Flow<BookEntity?> = bookDao.observe(id)
    fun observePages(bookId: Long): Flow<List<PageEntity>> = pageDao.observeForBook(bookId)
    fun observeRecentPages(bookId: Long, limit: Int): Flow<List<PageEntity>> = pageDao.observeRecent(bookId, limit)
    fun observePageCount(bookId: Long): Flow<Int> = pageDao.observeCount(bookId)
    fun observeCapturing(): Flow<List<BookEntity>> = bookDao.observeCapturing()
    fun observeCollections(): Flow<List<CollectionEntity>> = collectionDao.observeAll()
    fun observeCollectionIds(bookId: Long): Flow<List<Long>> = collectionDao.observeCollectionIdsForBook(bookId)
    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> = bookmarkDao.observeForBook(bookId)

    fun pageFile(bookId: Long, fileName: String): File = files.file(bookId, fileName)
    suspend fun getBook(id: Long): BookEntity? = bookDao.get(id)

    // ---- Livros ----
    suspend fun createBook(title: String?, sourceApp: SourceApp): Long {
        val finalTitle = title?.trim().takeUnless { it.isNullOrEmpty() } ?: defaultTitle(sourceApp)
        return bookDao.insert(BookEntity(title = finalTitle, titleSort = sortKey(finalTitle), sourceApp = sourceApp))
    }

    suspend fun rename(id: Long, title: String, author: String?) {
        val t = title.trim().ifEmpty { return }
        bookDao.rename(id, t, sortKey(t), author?.trim()?.ifEmpty { null })
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = bookDao.setFavorite(id, favorite)
    suspend fun setProgress(id: Long, position: Int) = bookDao.setProgress(id, position)
    /** Serializa as trocas de capa (evita arquivos de capa órfãos em trocas seguidas). */
    private val coverLock = Mutex()

    /** Usa uma página como capa (e descarta a capa própria, se houver). Banco primeiro, arquivo depois. */
    suspend fun setCover(id: Long, pageId: Long?) = withContext(Dispatchers.IO) {
        coverLock.withLock {
            val old = bookDao.get(id)?.customCoverFile
            bookDao.setCover(id, pageId)
            old?.let { files.delete(id, it) }
        }
    }

    /**
     * Capa própria a partir de uma imagem do Photo Picker. Decodifica já reduzida, grava e só então
     * troca no banco. Devolve falha (sem derrubar o app) se a imagem não abrir ou faltar espaço.
     */
    suspend fun setCustomCover(id: Long, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            coverLock.withLock {
                val name = files.writeCover(id, uri)
                val old = bookDao.get(id)?.customCoverFile
                try {
                    bookDao.setCustomCover(id, name)
                } catch (e: Exception) {
                    files.delete(id, name)
                    throw e
                }
                if (old != null) files.delete(id, old)
            }
        }
    }

    /** Volta à capa padrão (primeira página). */
    suspend fun removeCover(id: Long) = setCover(id, null)

    /** Conclui a captura e enfileira o OCR (busca no texto / voz alta). */
    suspend fun markReady(id: Long) {
        bookDao.setStatus(id, BookStatus.READY)
        work.enqueueOcr(id)
    }

    // ---- Compactar ----
    fun compact(id: Long, quality: PageQuality) = work.enqueueCompact(id, quality)
    fun observeCompact(id: Long) = work.observeCompact(id)

    // ---- Texto (OCR) e busca ----
    fun observeOcrProgress(bookId: Long): Flow<OcrProgress> = pageText.observeProgress(bookId)
    suspend fun pageText(pageId: Long): String? = pageText.textFor(pageId)

    suspend fun searchText(input: String): List<TextSearchHit> {
        val match = TextTools.ftsQuery(input) ?: return emptyList()
        return runCatching { pageText.search(match) }.getOrDefault(emptyList())
    }

    suspend fun searchInBook(bookId: Long, input: String): List<TextSearchHit> {
        val match = TextTools.ftsQuery(input) ?: return emptyList()
        return runCatching { pageText.searchInBook(bookId, match) }.getOrDefault(emptyList())
    }

    suspend fun deleteBook(id: Long) = withContext(Dispatchers.IO) {
        insertLock.withLock { // não corre junto com compactação/inserção de páginas
            bookDao.delete(id)
            files.deleteBook(id)
        }
    }

    /**
     * Compacta uma página (Liberar espaço). Sob o mesmo lock de inserir/excluir: se a página ou o
     * livro foram apagados no meio, não grava nada. Mantém a original se a nova não ficar menor.
     * @return bytes liberados.
     */
    suspend fun compactPage(bookId: Long, pageId: Long, quality: PageQuality): Long = withContext(Dispatchers.IO) {
        insertLock.withLock {
            val page = pageDao.getByIds(listOf(pageId)).firstOrNull() ?: return@withLock 0L
            if (bookDao.get(bookId) == null) return@withLock 0L
            // Já está no formato pedido (ou num menor): nada a fazer.
            if (files.isText(page.fileName)) return@withLock 0L
            if (quality == PageQuality.ECONOMICA && files.kind(bookId, page.fileName) != WebpFormat.Kind.LOSSLESS) return@withLock 0L
            val written = files.recode(bookId, page.fileName, quality)
            if (written.bytes >= page.byteSize) {
                files.delete(bookId, written.fileName)
                return@withLock 0L
            }
            try {
                pageDao.updateFile(page.id, written.fileName, written.bytes)
            } catch (e: Throwable) {
                files.delete(bookId, written.fileName)
                throw e
            }
            files.delete(bookId, page.fileName)
            page.byteSize - written.bytes
        }
    }

    suspend fun pageIds(bookId: Long): List<Long> = pageDao.getForBook(bookId).map { it.id }
    suspend fun refreshStats(bookId: Long) = bookDao.refreshStats(bookId)

    // ---- Páginas ----
    data class SavedPage(val pageId: Long, val bytes: Long, val encodeMs: Long, val position: Int)

    /**
     * Grava uma página no ponto [point] (fim, início ou depois de uma página). A inserção é uma
     * transação só; se ela falhar, o arquivo gravado é apagado.
     */
    suspend fun addPage(
        bookId: Long,
        bitmap: Bitmap,
        quality: PageQuality = PageQuality.MAXIMA,
        point: InsertPoint = InsertPoint.End,
    ): SavedPage = withContext(Dispatchers.IO) {
        val written = files.write(bookId, bitmap, quality)
        val inserted = try {
            insertLock.withLock {
                insertPageLocked(bookId, written, bitmap.width, bitmap.height, point).also { bookDao.refreshStats(bookId) }
            }
        } catch (e: Throwable) {
            files.delete(bookId, written.fileName)
            throw e
        }
        SavedPage(inserted.id, written.bytes, written.encodeMs, inserted.position)
    }

    /** Insere a página (chamar dentro de [insertLock]). */
    private suspend fun insertPageLocked(
        bookId: Long,
        written: PageFileStore.Written,
        width: Int,
        height: Int,
        point: InsertPoint,
    ): EditorTransactions.Inserted = editor.insertAt(
        PageEntity(
            bookId = bookId, position = 0, fileName = written.fileName,
            width = width, height = height, byteSize = written.bytes,
        ),
        point,
    )

    /**
     * Página anterior a [pageId] no livro (para reancorar uma inserção quando a âncora é excluída).
     * [InsertPoint.Start] se ela é a primeira; null se ela não existe.
     */
    suspend fun pointBefore(bookId: Long, pageId: Long): InsertPoint? = withContext(Dispatchers.IO) {
        val ids = pageDao.getForBook(bookId).map { it.id }
        val i = ids.indexOf(pageId)
        when {
            i < 0 -> null
            i == 0 -> InsertPoint.Start
            else -> InsertPoint.After(ids[i - 1])
        }
    }

    /** Resultado de importar imagens da galeria. */
    data class ImportResult(val added: Int, val failed: Int)

    /**
     * Importa imagens da galeria como páginas, em sequência a partir de [point]. Imagens que não
     * abrem (ou não cabem na memória) são puladas; se a gravação no banco falhar, o arquivo é
     * apagado. Enfileira o OCR das novas.
     */
    suspend fun importImages(
        bookId: Long,
        uris: List<Uri>,
        point: InsertPoint,
        quality: PageQuality,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        var added = 0
        var failed = 0
        var next = point
        try {
            uris.forEachIndexed { i, uri ->
                onProgress(i, uris.size)
                val page = try {
                    files.writePageFromUri(bookId, uri, quality)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    return@forEachIndexed
                } catch (e: OutOfMemoryError) {
                    failed++
                    return@forEachIndexed
                }
                try {
                    insertLock.withLock {
                        if (bookDao.get(bookId) == null) {
                            files.delete(bookId, page.written.fileName)
                            return@withContext ImportResult(added, failed + uris.size - i)
                        }
                        val inserted = insertPageLocked(bookId, page.written, page.width, page.height, next)
                        bookDao.refreshStats(bookId)
                        next = PageOrder.chain(next, inserted.id)
                    }
                    added++
                } catch (e: Throwable) {
                    files.delete(bookId, page.written.fileName)
                    if (e is CancellationException) throw e
                    failed++
                }
            }
            onProgress(uris.size, uris.size)
        } finally {
            if (added > 0) work.enqueueOcr(bookId)
        }
        ImportResult(added, failed)
    }

    /** Move as páginas selecionadas para que a primeira fique na posição [targetIndex] (0-based). */
    suspend fun movePages(bookId: Long, selected: Set<Long>, targetIndex: Int) = withContext(Dispatchers.IO) {
        insertLock.withLock {
            val ids = pageDao.getForBook(bookId).map { it.id }
            editor.reorder(bookId, PageOrder.moveTo(ids, selected, targetIndex))
        }
    }

    /**
     * Exclui páginas. Usa o mesmo lock da inserção (a bolha pode excluir durante uma captura) e só
     * esquece a página-capa, nunca a capa própria.
     */
    suspend fun deletePages(bookId: Long, pageIds: Set<Long>) = withContext(Dispatchers.IO) {
        insertLock.withLock {
            val pages = pageDao.getByIds(pageIds.toList())
            pageDao.deleteAll(pages)
            pages.forEach { files.delete(bookId, it.fileName) }
            val remaining = pageDao.getForBook(bookId)
            editor.reorder(bookId, remaining.map { it.id })
            val book = bookDao.get(bookId)
            if (book?.coverPageId != null && book.coverPageId in pageIds) bookDao.clearCoverPage(bookId)
            bookDao.refreshStats(bookId)
        }
    }

    /**
     * Grava a nova ordem. Páginas que chegaram depois de a lista ser montada (captura em andamento)
     * vão para o fim, e ids que já não existem são ignorados: as posições nunca se repetem.
     */
    suspend fun reorderPages(bookId: Long, orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        insertLock.withLock {
            editor.reorder(bookId, PageOrder.reconcile(pageDao.getForBook(bookId).map { it.id }, orderedIds))
        }
    }

    suspend fun rotatePage(pageId: Long, currentRotation: Int) =
        pageDao.setRotation(pageId, (currentRotation + 90) % 360)

    // ---- Coleções e marcadores ----
    suspend fun createCollection(name: String): Long = collectionDao.insert(CollectionEntity(name = name.trim()))
    suspend fun deleteCollection(id: Long) = collectionDao.delete(id)
    suspend fun setInCollection(bookId: Long, collectionId: Long, inCollection: Boolean) {
        val ref = BookCollectionCrossRef(bookId, collectionId)
        if (inCollection) collectionDao.addBook(ref) else collectionDao.removeBook(ref)
    }

    suspend fun toggleBookmark(bookId: Long, pageId: Long, bookmarked: Boolean) {
        if (bookmarked) bookmarkDao.deleteForPage(pageId) else bookmarkDao.insert(BookmarkEntity(bookId = bookId, pageId = pageId))
    }

    fun storageBytes(): Long = files.totalBytes()

    companion object {
        /** Ordem de prioridade da capa: própria > página escolhida > primeira página. */
        fun resolveCover(custom: String?, chosenPage: String?, firstPage: String?): String? =
            custom ?: chosenPage ?: firstPage

        private val leadingArticles = Regex("^(o|a|os|as|um|uma|the|an?)\\s+")

        fun sortKey(title: String): String {
            val noAccents = Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return noAccents.replace(leadingArticles, "").trim()
        }

        fun defaultTitle(sourceApp: SourceApp): String {
            val stamp = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date())
            // "Site ou outro app" é longo para título: usa só "Captura".
            val prefix = if (sourceApp == SourceApp.OTHER) "Captura" else sourceApp.label
            return "$prefix $stamp"
        }
    }
}
