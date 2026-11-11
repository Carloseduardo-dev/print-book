package com.printbook.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.printbook.app.data.db.dao.BookDao
import com.printbook.app.data.db.dao.BookmarkDao
import com.printbook.app.data.db.dao.CollectionDao
import com.printbook.app.data.db.dao.EditorTransactions
import com.printbook.app.data.db.dao.PageDao
import com.printbook.app.data.db.entity.BookCollectionCrossRef
import com.printbook.app.data.db.entity.BookEntity
import com.printbook.app.data.db.entity.BookmarkEntity
import com.printbook.app.data.db.entity.CollectionEntity
import com.printbook.app.data.db.entity.PageEntity

@Database(
    entities = [
        BookEntity::class,
        PageEntity::class,
        CollectionEntity::class,
        BookCollectionCrossRef::class,
        BookmarkEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PrintBookDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun pageDao(): PageDao
    abstract fun collectionDao(): CollectionDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun editorTransactions(): EditorTransactions
}
