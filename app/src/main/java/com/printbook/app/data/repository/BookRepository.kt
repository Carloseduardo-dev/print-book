package com.printbook.app.data.repository

import android.graphics.Bitmap
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
import com.printbook.app.data.prefs.PageQuality
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
    suspend fun setCover(id: Long, pageId: Long?) = bookDao.setCover(id, pageId)
    /** Conclui a captura e enfileira o OCR (busca no texto / voz alta). */
    suspend fun markReady(id: Long) {
        bookDao.setStatus(id, BookStatus.READY)
        work.enqueueOcr(id)
    }

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
        bookDao.delete(id)
        files.deleteBook(id)
    }

    // ---- Páginas ----
    data class SavedPage(val pageId: Long, val bytes: Long, val encodeMs: Long, val position: Int)

    suspend fun addPage(bookId: Long, bitmap: Bitmap, quality: PageQuality = PageQuality.MAXIMA): SavedPage = withContext(Dispatchers.IO) {
        val written = files.write(bookId, bitmap, quality)
        insertLock.withLock {
            val position = pageDao.maxPosition(bookId) + 1
            val id = pageDao.insert(
                PageEntity(
                    bookId = bookId, position = position, fileName = written.fileName,
                    width = bitmap.width, height = bitmap.height, byteSize = written.bytes,
                ),
            )
            bookDao.refreshStats(bookId)
            SavedPage(id, written.bytes, written.encodeMs, position)
        }
    }

    suspend fun deletePages(bookId: Long, pageIds: Set<Long>) = withContext(Dispatchers.IO) {
        val pages = pageDao.getByIds(pageIds.toList())
        pageDao.deleteAll(pages)
        pages.forEach { files.delete(bookId, it.fileName) }
        val remaining = pageDao.getForBook(bookId)
        editor.reorder(bookId, remaining.map { it.id })
        val book = bookDao.get(bookId)
        if (book?.coverPageId != null && book.coverPageId in pageIds) bookDao.setCover(bookId, null)
        bookDao.refreshStats(bookId)
    }

    suspend fun reorderPages(bookId: Long, orderedIds: List<Long>) = editor.reorder(bookId, orderedIds)

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
        private val leadingArticles = Regex("^(o|a|os|as|um|uma|the|an?)\\s+")

        fun sortKey(title: String): String {
            val noAccents = Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return noAccents.replace(leadingArticles, "").trim()
        }

        fun defaultTitle(sourceApp: SourceApp): String {
            val stamp = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date())
            return "${sourceApp.label} $stamp"
        }
    }
}
