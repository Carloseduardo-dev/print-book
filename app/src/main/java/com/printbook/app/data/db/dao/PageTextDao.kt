package com.printbook.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.printbook.app.data.db.entity.OcrState
import com.printbook.app.data.db.entity.PageEntity
import com.printbook.app.data.db.entity.PageTextEntity
import kotlinx.coroutines.flow.Flow

/** Resultado de busca no texto: uma página de um livro, com trecho destacado entre [ ]. */
data class TextSearchHit(
    val bookId: Long,
    val bookTitle: String,
    val pageId: Long,
    val position: Int,
    val snippet: String,
)

/** Progresso do OCR de um livro. */
data class OcrProgress(val total: Int, val done: Int, val failed: Int)

@Dao
interface PageTextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(text: PageTextEntity)

    @Query("SELECT text FROM page_text WHERE pageId = :pageId")
    suspend fun textFor(pageId: Long): String?

    @Query("UPDATE pages SET ocrState = :state WHERE id = :pageId")
    suspend fun setState(pageId: Long, state: OcrState)

    @Query("SELECT * FROM pages WHERE bookId = :bookId AND ocrState = 'PENDING' ORDER BY position LIMIT :limit")
    suspend fun pendingPages(bookId: Long, limit: Int): List<PageEntity>

    @Query(
        """SELECT DISTINCT b.id FROM books b JOIN pages p ON p.bookId = b.id
           WHERE p.ocrState = 'PENDING' AND b.status = 'READY'""",
    )
    suspend fun booksWithPendingOcr(): List<Long>

    @Query(
        """SELECT COUNT(*) AS total,
                  COALESCE(SUM(CASE WHEN ocrState = 'DONE' THEN 1 ELSE 0 END), 0) AS done,
                  COALESCE(SUM(CASE WHEN ocrState = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed
           FROM pages WHERE bookId = :bookId""",
    )
    fun observeProgress(bookId: Long): Flow<OcrProgress>

    @Query(
        """SELECT p.bookId AS bookId, b.title AS bookTitle, p.id AS pageId, p.position AS position,
                  snippet(page_text_fts, '[', ']', '…', -1, 14) AS snippet
           FROM page_text_fts
           JOIN page_text t ON t.pageId = page_text_fts.rowid
           JOIN pages p ON p.id = t.pageId
           JOIN books b ON b.id = p.bookId
           WHERE page_text_fts MATCH :match
           ORDER BY b.title, p.position
           LIMIT :limit""",
    )
    suspend fun search(match: String, limit: Int = 200): List<TextSearchHit>

    @Query(
        """SELECT p.bookId AS bookId, b.title AS bookTitle, p.id AS pageId, p.position AS position,
                  snippet(page_text_fts, '[', ']', '…', -1, 14) AS snippet
           FROM page_text_fts
           JOIN page_text t ON t.pageId = page_text_fts.rowid
           JOIN pages p ON p.id = t.pageId
           JOIN books b ON b.id = p.bookId
           WHERE page_text_fts MATCH :match AND p.bookId = :bookId
           ORDER BY p.position""",
    )
    suspend fun searchInBook(bookId: Long, match: String): List<TextSearchHit>
}
