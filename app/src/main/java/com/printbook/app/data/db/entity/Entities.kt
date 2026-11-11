package com.printbook.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SourceApp(val label: String, val packageName: String?) {
    KINDLE("Kindle", "com.amazon.kindle"),
    PLAY_BOOKS("Play Livros", "com.google.android.apps.books"),
    MINHA_BIBLIOTECA("Minha Biblioteca", "com.minha.biblioteca"),
    OTHER("Outro app", null);

    companion object {
        fun fromPackage(pkg: String?): SourceApp = entries.firstOrNull { it.packageName == pkg } ?: OTHER
    }
}

enum class BookStatus { CAPTURING, READY }

@Entity(
    tableName = "books",
    indices = [Index("titleSort"), Index("isFavorite"), Index("lastReadAt")],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val titleSort: String,
    val author: String? = null,
    val sourceApp: SourceApp = SourceApp.OTHER,
    /** Sem FK para evitar ciclo books<->pages; nulo = usar a primeira página. */
    val coverPageId: Long? = null,
    val isFavorite: Boolean = false,
    val lastReadPosition: Int = 0,
    val lastReadAt: Long? = null,
    val pageCount: Int = 0,
    val totalBytes: Long = 0,
    val status: BookStatus = BookStatus.CAPTURING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "pages",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "position"])],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val position: Int,
    val fileName: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val rotation: Int = 0,
    val capturedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "book_collections",
    primaryKeys = ["bookId", "collectionId"],
    foreignKeys = [
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(CollectionEntity::class, ["id"], ["collectionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("collectionId")],
)
data class BookCollectionCrossRef(
    val bookId: Long,
    val collectionId: Long,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PageEntity::class, ["id"], ["pageId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("bookId"), Index("pageId", unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val pageId: Long,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
