package com.example.vikas.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import androidx.room.ColumnInfo

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val author: String?,
    val format: String,          // epub, pdf, cbz, txt
    val coverPath: String?,
    val addedAt: Long,
    val lastPage: Int = 0,
    val totalPages: Int = 0,
    val lastOpenedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false
)


@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUri: String,
    val page: Int,
    val note: String? = null
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

// Many-to-many link: one book can be in several collections, and vice versa
@Entity(
    tableName = "collection_books",
    primaryKeys = ["collectionId", "bookUri"],
    indices = [Index("bookUri")]
)
data class CollectionBookEntity(
    val collectionId: Long,
    val bookUri: String
)

@Dao
interface BookDao {

    @Query("SELECT COUNT(*) FROM books WHERE uri = :uri")
    suspend fun exists(uri: String): Int

    @Query("UPDATE books SET hidden = 0 WHERE uri = :uri")
    suspend fun unhide(uri: String)

    @Query("SELECT * FROM books WHERE hidden = 0 ORDER BY lastOpenedAt DESC, title ASC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE coverPath IS NULL AND hidden = 0")
    suspend fun getWithoutCover(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(books: List<BookEntity>)

    @Query("UPDATE books SET lastPage = :page, totalPages = :total, lastOpenedAt = :now WHERE uri = :uri")
    suspend fun updateProgress(uri: String, page: Int, total: Int, now: Long)


    @Query("UPDATE books SET title = :title, author = :author, coverPath = :cover WHERE uri = :uri")
    suspend fun updateMetadata(uri: String, title: String, author: String?, cover: String?)



    @Query("UPDATE books SET hidden = 1 WHERE uri = :uri")
    suspend fun hide(uri: String)

    @Query("UPDATE books SET hidden = 0 WHERE hidden = 1")
    suspend fun restoreAllHidden()

    @Query("SELECT COUNT(*) FROM books WHERE hidden = 1")
    fun observeHiddenCount(): Flow<Int>

}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookUri = :uri ORDER BY page ASC")
    fun observeFor(uri: String): Flow<List<BookmarkEntity>>


    @Insert
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE bookUri = :uri AND page = :page")
    suspend fun deleteAt(uri: String, page: Int)

    @Query("UPDATE bookmarks SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)


}

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collection_books")
    fun observeAllLinks(): Flow<List<CollectionBookEntity>>

    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM collection_books WHERE collectionId = :id")
    suspend fun clearBooks(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBook(link: CollectionBookEntity)

    @Query("DELETE FROM collection_books WHERE collectionId = :collectionId AND bookUri = :bookUri")
    suspend fun removeBook(collectionId: Long, bookUri: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBooks(links: List<CollectionBookEntity>)

}

@Entity(tableName = "reading_sessions")
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUri: String,
    val startedAt: Long,
    val durationSec: Int,
    val pagesViewed: Int
)

@Dao
interface StatsDao {
    @Insert
    suspend fun insert(session: ReadingSessionEntity)

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<ReadingSessionEntity>>
}

@Entity(tableName = "dictionary_cache")
data class DictionaryEntity(
    @PrimaryKey val word: String,
    val json: String,
    val fetchedAt: Long
)

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dictionary_cache WHERE word = :word")
    suspend fun get(word: String): DictionaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: DictionaryEntity)

    @Query("SELECT word FROM dictionary_cache ORDER BY fetchedAt DESC LIMIT :n")
    fun recent(n: Int): Flow<List<String>>
}

@Entity(tableName = "highlights", indices = [Index("bookUri")])
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUri: String,
    val chapter: Int,        // EPUB chapter number; always 0 for TXT
    val startOffset: Int,    // character positions inside that chapter's (or file's) text
    val endOffset: Int,
    val text: String,        // the highlighted words, for the list
    val color: Int,          // 0 yellow, 1 green, 2 blue, 3 pink
    val note: String?,
    val createdAt: Long
)

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE bookUri = :uri ORDER BY chapter ASC, startOffset ASC")
    fun observeFor(uri: String): Flow<List<HighlightEntity>>

    @Insert
    suspend fun insert(h: HighlightEntity): Long

    @Query("UPDATE highlights SET color = :color, note = :note WHERE id = :id")
    suspend fun update(id: Long, color: Int, note: String?)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun delete(id: Long)
}



// Version 1 -> 2: add the two collection tables. Existing data is untouched.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `collections` " +
                    "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `collection_books` " +
                    "(`collectionId` INTEGER NOT NULL, `bookUri` TEXT NOT NULL, " +
                    "PRIMARY KEY(`collectionId`, `bookUri`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_collection_books_bookUri` " +
                    "ON `collection_books` (`bookUri`)"
        )
    }
}

// Version 2 -> 3: add the reading_sessions table. Existing data is untouched.
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reading_sessions` " +
                    "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`bookUri` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                    "`durationSec` INTEGER NOT NULL, `pagesViewed` INTEGER NOT NULL)"
        )
    }
}

// Version 3 -> 4: add the dictionary cache. Existing data is untouched.
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dictionary_cache` " +
                    "(`word` TEXT NOT NULL, `json` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`word`))"
        )
    }
}

// Version 4 -> 5: books can be hidden (removed from the library). Existing data is untouched.
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `books` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0")
    }
}

// Version 5 -> 6: add the highlights table. Existing data is untouched.
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `highlights` " +
                    "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookUri` TEXT NOT NULL, " +
                    "`chapter` INTEGER NOT NULL, `startOffset` INTEGER NOT NULL, " +
                    "`endOffset` INTEGER NOT NULL, `text` TEXT NOT NULL, `color` INTEGER NOT NULL, " +
                    "`note` TEXT, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_highlights_bookUri` ON `highlights` (`bookUri`)"
        )
    }
}

@Database(
    entities = [
        BookEntity::class,
        BookmarkEntity::class,
        CollectionEntity::class,
        CollectionBookEntity::class,
        ReadingSessionEntity::class,
        DictionaryEntity::class,
        HighlightEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun collectionDao(): CollectionDao

    abstract fun statsDao(): StatsDao

    abstract fun dictionaryDao(): DictionaryDao

    abstract fun highlightDao(): HighlightDao

    companion object {
        @Volatile private var INSTANCE: AppDb? = null

        fun get(context: Context): AppDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext, AppDb::class.java, "vikas.db"
            )

                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                .also { INSTANCE = it }
        }
    }
}