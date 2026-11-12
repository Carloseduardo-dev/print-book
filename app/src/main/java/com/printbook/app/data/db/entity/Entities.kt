package com.printbook.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SourceApp(val label: String, val packageName: String?) {
    KINDLE("Kindle", "com.amazon.kindle"),
    PLAY_BOOKS("Play Livros", "com.google.android.apps.books"),
    MINHA_BIBLIOTECA("Minha Biblioteca", "com.minha.biblioteca"),
    OTHER("Site ou outro app", null);

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
    /** Capa própria (imagem escolhida pelo usuário), arquivo em books/{id}/. Tem prioridade. */
    val customCoverFile: String? = null,
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
    /** Estado do OCR da página (busca no texto e leitura em voz alta). */
    @ColumnInfo(defaultValue = "PENDING") val ocrState: OcrState = OcrState.PENDING,
)

enum class OcrState { PENDING, DONE, FAILED }

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

/** Texto reconhecido (OCR) de uma página. pageId é INTEGER PRIMARY KEY = rowid do FTS. */
@Entity(
    tableName = "page_text",
    foreignKeys = [ForeignKey(PageEntity::class, ["id"], ["pageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class PageTextEntity(
    @PrimaryKey val pageId: Long,
    val bookId: Long,
    val text: String,
    val processedAt: Long = System.currentTimeMillis(),
)

/** Índice de busca (FTS4) sobre [PageTextEntity], ignorando acentos. */
@Fts4(
    contentEntity = PageTextEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
)
@Entity(tableName = "page_text_fts")
data class PageTextFts(val text: String)
