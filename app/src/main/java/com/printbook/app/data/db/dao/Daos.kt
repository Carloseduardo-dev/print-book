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

    @Query("UPDATE books SET coverPageId = :pageId, updatedAt = :now WHERE id = :id")
    suspend fun setCover(id: Long, pageId: Long?, now: Long = System.currentTimeMillis())

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

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY position")
    suspend fun getForBook(bookId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<PageEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM pages WHERE bookId = :bookId")
    suspend fun maxPosition(bookId: Long): Int

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

    /** Regrava as posições 0..n-1 na ordem dada. */
    @Transaction
    open suspend fun reorder(bookId: Long, orderedIds: List<Long>) {
        val byId = pages(bookId).associateBy { it.id }
        update(orderedIds.mapIndexedNotNull { index, id -> byId[id]?.copy(position = index) })
    }
}
