package com.example.vikas.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.vikas.data.db.AppDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupSummary(val books: Int, val bookmarks: Int)

class BackupManager(context: Context) {

    private val ctx = context.applicationContext
    private val dbFile: File get() = ctx.getDatabasePath("vikas.db")
    private val settingsFile: File get() = File(ctx.filesDir, "datastore/settings.preferences_pb")

    private companion object {
        const val DB = "vikas.db"
        const val SETTINGS = "settings.preferences_pb"
        const val MANIFEST = "manifest.json"
        val ALLOWED = setOf(DB, SETTINGS, MANIFEST) // fixed names only: no path tricks
    }

    suspend fun backup(dest: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val sdb = AppDb.get(ctx).openHelper.writableDatabase

        // Flush the write-ahead log into the main file so vikas.db is complete
        sdb.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }

        val summary = BackupSummary(count(sdb, "books"), count(sdb, "bookmarks"))
        val manifest = JSONObject()
            .put("app", "VIKAS")
            .put("dbVersion", sdb.version)
            .put("createdAt", System.currentTimeMillis())
            .put("books", summary.books)
            .put("bookmarks", summary.bookmarks)
            .toString(2)

        val out = ctx.contentResolver.openOutputStream(dest, "w")
            ?: error("Cannot write to the chosen location")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(DB))
            dbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            if (settingsFile.exists()) {
                zip.putNextEntry(ZipEntry(SETTINGS))
                settingsFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        summary
    }

    suspend fun restore(src: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            // 1. Unpack into a temp folder. The live database is not touched yet.
            val input = ctx.contentResolver.openInputStream(src)
                ?: error("Cannot read the chosen file")
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name in ALLOWED) {
                        File(tmp, entry.name).outputStream().use { zip.copyTo(it) }
                    }
                    entry = zip.nextEntry
                }
            }
            val newDb = File(tmp, DB)
            if (!newDb.exists()) throw IllegalArgumentException("This is not a VIKAS backup")

            // 2. Validate it, and clear stale cover paths (covers are regenerated)
            val currentVersion = AppDb.get(ctx).openHelper.readableDatabase.version
            val summary = SQLiteDatabase.openDatabase(
                newDb.path, null, SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                if (db.version < 1) throw IllegalArgumentException("This is not a VIKAS backup")
                if (db.version > currentVersion) {
                    throw IllegalArgumentException(
                        "This backup was made by a newer version of VIKAS"
                    )
                }
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='books'", null
                ).use {
                    if (!it.moveToFirst()) throw IllegalArgumentException("This is not a VIKAS backup")
                }
                db.execSQL("UPDATE books SET coverPath = NULL")
                BackupSummary(count(db, "books"), count(db, "bookmarks"))
            }
            File(newDb.path + "-wal").delete()
            File(newDb.path + "-shm").delete()

            // 3. Swap files. The old connection keeps its (now unlinked) files until the
            //    app restarts, so nothing crashes while the "Restore complete" dialog shows.
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                File(dbFile.path + suffix).delete()
            }
            newDb.copyTo(dbFile, overwrite = true)

            val newSettings = File(tmp, SETTINGS)
            if (newSettings.exists()) {
                settingsFile.parentFile?.mkdirs()
                newSettings.copyTo(settingsFile, overwrite = true)
            }
            summary
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun count(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}