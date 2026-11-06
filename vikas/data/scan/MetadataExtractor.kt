package com.example.vikas.data.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.vikas.data.db.AppDb
import com.example.vikas.data.db.BookEntity
import com.example.vikas.data.readers.CbzBookReader
import com.example.vikas.data.readers.EpubBook
import com.example.vikas.data.readers.PdfBookReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MetadataExtractor(private val context: Context) {

    private val dao = AppDb.get(context).bookDao()
    private val mutex = Mutex()
    private val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
    private val coverWidth = 300

    // Fills in title, author and cover for every book that has no cover yet
    suspend fun processPending() = mutex.withLock {
        withContext(Dispatchers.IO) {
            for (book in dao.getWithoutCover()) {
                try {
                    process(book)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Skip a broken file; it will be retried on the next launch
                }
            }
        }
    }

    private suspend fun process(book: BookEntity) {
        val uri = Uri.parse(book.uri)
        var title: String? = null
        var author: String? = null
        var cover: Bitmap? = null

        when (book.format) {
            "pdf" -> PdfBookReader(context, uri).use {
                if (it.pageCount > 0) cover = it.renderPage(0, coverWidth)
            }
            "cbz" -> CbzBookReader(context, uri).use {
                if (it.pageCount > 0) cover = it.renderPage(0, coverWidth)
            }
            "epub" -> EpubBook.open(context, uri).use { epub ->
                title = epub.title?.takeIf { it.isNotBlank() }
                author = epub.author?.takeIf { it.isNotBlank() }
                cover = epub.coverPath?.let { decodeSampled(epub, it) }
            }
        }

        val coverFile = cover?.let { saveCover(book.uri, scaleDown(it)) }
        val newTitle = title ?: book.title
        if (coverFile != null || author != null || newTitle != book.title) {
            dao.updateMetadata(book.uri, newTitle, author, coverFile)
        }
    }

    private fun decodeSampled(epub: EpubBook, path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        epub.openResource(path)?.second?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = (bounds.outWidth / coverWidth).coerceAtLeast(1)
        }
        return epub.openResource(path)?.second?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun scaleDown(bmp: Bitmap): Bitmap {
        if (bmp.width <= coverWidth) return bmp
        val h = (bmp.height.toFloat() * coverWidth / bmp.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, coverWidth, h, true)
    }

    private fun saveCover(bookUri: String, bmp: Bitmap): String {
        val name = UUID.nameUUIDFromBytes(bookUri.toByteArray()).toString() + ".jpg"
        val file = File(coverDir, name)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file.absolutePath
    }
}