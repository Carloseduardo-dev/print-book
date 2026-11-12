package com.printbook.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.printbook.app.data.db.entity.BookCollectionCrossRef
import com.printbook.app.data.db.entity.BookEntity
import com.printbook.app.data.db.entity.BookmarkEntity
import com.printbook.app.data.db.entity.CollectionEntity
import com.printbook.app.data.db.entity.PageEntity
import com.printbook.app.data.repository.InsertPoint
import com.printbook.app.data.repository.PageOrder
import kotlinx.coroutines.flow.Flow

/** Livro + arquivo da capa resolvido (capa escolhida ou primeira página). */
data class BookWithCover(
    val id: Long,
    val title: String,
    val author: String?,
    val sourceApp: com.printbook.app.data.db.entity.SourceApp,
    val isFavorite: Boolean,
    val lastReadPosition: Int,
    val lastReadAt: Long?,
    val pageCount: Int,
    val totalBytes: Long,
    val status: com.printbook.app.data.db.entity.BookStatus,
    val createdAt: Long,
    val coverFile: String?,
)

private const val BOOK_WITH_COVER = """
    SELECT b.id, b.title, b.author, b.sourceApp, b.isFavorite, b.lastReadPosition, b.lastReadAt,
           b.pageCount, b.totalBytes, b.status, b.createdAt,
           COALESCE(
             b.customCoverFile,
             (SELECT p.fileName FROM pages p WHERE p.id = b.coverPageId),
             (SELECT p.fileName FROM pages p WHERE p.bookId = b.id ORDER BY p.position LIMIT 1)
           ) AS coverFile
    FROM books b
"""

@Dao
interface BookDao {
    @Insert suspend fun insert(book: BookEntity): Long
    @Update suspend fun update(book: BookEntity)
    @Query("DELETE FROM books WHERE id = :id") suspend fun delete(id: Long)

    @Query("SELECT * FROM books WHERE id = :id") suspend fun get(id: Long): BookEntity?
    @Query("SELECT * FROM books WHERE id = :id") fun observe(id: Long): Flow<BookEntity?>

    @Query("$BOOK_WITH_COVER ORDER BY b.updatedAt DESC")
    fun observeAllWithCover(): Flow<List<BookWithCover>>

    @Query("$BOOK_WITH_COVER WHERE b.id IN (SELECT bookId FROM book_collections WHERE collectionId = :collectionId) ORDER BY b.titleSort")
    fun observeInCollection(collectionId: Long): Flow<List<BookWithCover>>

    @Query("$BOOK_WITH_COVER WHERE b.lastReadAt IS NOT NULL AND b.pageCount > 0 ORDER BY b.lastReadAt DESC LIMIT :limit")
    fun observeRecentlyRead(limit: Int): Flow<List<BookWithCover>>

    @Query("$BOOK_WITH_COVER WHERE b.pageCount > 0 ORDER BY b.createdAt DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<BookWithCover>>

    @Query("SELECT * FROM books WHERE status = 'CAPTURING' ORDER BY updatedAt DESC")
    fun observeCapturing(): Flow<List<BookEntity>>

    @Query("UPDATE books SET title = :title, titleSort = :titleSort, author = :author, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, titleSort: String, author: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE books SET lastReadPosition = :position, lastReadAt = :now WHERE id = :id")
    suspend fun setProgress(id: Long, position: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET coverPageId = :pageId, customCoverFile = NULL, updatedAt = :now WHERE id = :id")
    suspend fun setCover(id: Long, pageId: Long?, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET customCoverFile = :fileName, updatedAt = :now WHERE id = :id")
    suspend fun setCustomCover(id: Long, fileName: String?, now: Long = System.currentTimeMillis())

    /** Só esquece a página escolhida como capa (a capa própria, se houver, continua valendo). */
    @Query("UPDATE books SET coverPageId = NULL, updatedAt = :now WHERE id = :id")
    suspend fun clearCoverPage(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE books SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: com.printbook.app.data.db.entity.BookStatus, now: Long = System.currentTimeMillis())

    @Query(
        """UPDATE books SET
             pageCount = (SELECT COUNT(*) FROM pages WHERE bookId = :id),
             totalBytes = (SELECT COALESCE(SUM(byteSize), 0) FROM pages WHERE bookId = :id),
             updatedAt = :now
           WHERE id = :id""",
    )
    suspend fun refreshStats(id: Long, now: Long = System.currentTimeMillis())
}

@Dao
interface PageDao {
    @Insert suspend fun insert(page: PageEntity): Long
    @Update suspend fun updateAll(pages: List<PageEntity>)
    @Delete suspend fun deleteAll(pages: List<PageEntity>)

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY position")
    fun observeForBook(bookId: Long): Flow<List<PageEntity>>

    /**
     * Últimas páginas CAPTURADAS (mais recentes primeiro): preview da bolha sem carregar o livro
     * inteiro. Por data de captura, não por posição: páginas inseridas no meio também aparecem.
     */
    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY capturedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(bookId: Long, limit: Int): Flow<List<PageEntity>>

    @Query("SELECT COUNT(*) FROM pages WHERE bookId = :bookId")
    fun observeCount(bookId: Long): Flow<Int>

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY position")
    suspend fun getForBook(bookId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<PageEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM pages WHERE bookId = :bookId")
    suspend fun maxPosition(bookId: Long): Int

    /** Troca o arquivo de uma página (compactar livro). */
    @Query("UPDATE pages SET fileName = :fileName, byteSize = :bytes WHERE id = :id")
    suspend fun updateFile(id: Long, fileName: String, bytes: Long)

    @Query("UPDATE pages SET rotation = :rotation WHERE id = :id")
    suspend fun setRotation(id: Long, rotation: Int)
}

@Dao
interface CollectionDao {
    @Insert suspend fun insert(collection: CollectionEntity): Long
    @Query("DELETE FROM collections WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM collections ORDER BY position, name") fun observeAll(): Flow<List<CollectionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addBook(ref: BookCollectionCrossRef)
    @Delete suspend fun removeBook(ref: BookCollectionCrossRef)

    @Query("SELECT collectionId FROM book_collections WHERE bookId = :bookId")
    fun observeCollectionIdsForBook(bookId: Long): Flow<List<Long>>
}

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(bookmark: BookmarkEntity): Long
    @Query("DELETE FROM bookmarks WHERE pageId = :pageId") suspend fun deleteForPage(pageId: Long)
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt") fun observeForBook(bookId: Long): Flow<List<BookmarkEntity>>
}

/** Operações que precisam de transação entre DAOs. */
@Dao
abstract class EditorTransactions {
    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY position")
    abstract suspend fun pages(bookId: Long): List<PageEntity>

    @Update abstract suspend fun update(pages: List<PageEntity>)

    @Insert protected abstract suspend fun insertPage(page: PageEntity): Long

    @Query("SELECT COALESCE(MAX(position), -1) FROM pages WHERE bookId = :bookId")
    protected abstract suspend fun maxPosition(bookId: Long): Int

    @Query("SELECT position FROM pages WHERE bookId = :bookId AND id = :pageId")
    protected abstract suspend fun positionOf(bookId: Long, pageId: Long): Int?

    @Query("UPDATE pages SET position = position + 1 WHERE bookId = :bookId AND position >= :from")
    protected abstract suspend fun shiftPagesFrom(bookId: Long, from: Int)

    /** Leitura em andamento (null = nunca lido: não há posição a preservar). */
    @Query("SELECT lastReadPosition FROM books WHERE id = :bookId AND lastReadAt IS NOT NULL")
    protected abstract suspend fun lastReadPosition(bookId: Long): Int?

    @Query("UPDATE books SET lastReadPosition = :position WHERE id = :bookId")
    protected abstract suspend fun setLastReadPosition(bookId: Long, position: Int)

    /** Página gravada: id e posição em que entrou. */
    data class Inserted(val id: Long, val position: Int)

    /**
     * Insere a página no ponto pedido numa única transação: abre espaço deslocando as seguintes
     * (uma consulta só, sem regravar o livro todo) e mantém a leitura na mesma página.
     */
    @Transaction
    open suspend fun insertAt(page: PageEntity, point: InsertPoint): Inserted {
        val bookId = page.bookId
        val max = maxPosition(bookId)
        val anchor = (point as? InsertPoint.After)?.let { positionOf(bookId, it.pageId) }
        val position = PageOrder.insertPosition(point, max, anchor)
        if (position <= max) {
            shiftPagesFrom(bookId, position)
            lastReadPosition(bookId)?.let { if (it >= position) setLastReadPosition(bookId, it + 1) }
        }
        return Inserted(insertPage(page.copy(position = position)), position)
    }

    /** Regrava as posições 0..n-1 na ordem dada; a leitura acompanha a página em que estava. */
    @Transaction
    open suspend fun reorder(bookId: Long, orderedIds: List<Long>) {
        val old = pages(bookId)
        val byId = old.associateBy { it.id }
        val ordered = orderedIds.filter { it in byId }
        update(ordered.mapIndexedNotNull { index, id -> byId[id]?.takeIf { it.position != index }?.copy(position = index) })
        lastReadPosition(bookId)?.let { lastRead ->
            PageOrder.followLastRead(old.map { it.id to it.position }, lastRead, ordered)?.let { setLastReadPosition(bookId, it) }
        }
    }
}
