package com.example.vikas.data.readers

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

// Pulls the text of single PDF pages on demand and keeps recent pages in a size-limited cache
class PdfTextExtractor(
    private val context: Context,
    private val uri: Uri
) : Closeable {

    private var doc: PDDocument? = null
    private var failed = false
    private val mutex = Mutex() // PDFBox documents are not thread-safe

    // Cache limited by total characters (about 8 MB of text), not by page count
    private val cache = object : LruCache<Int, String>(4_000_000) {
        override fun sizeOf(key: Int, value: String): Int = value.length.coerceAtLeast(1)
    }

    private fun open(): PDDocument? {
        doc?.let { return it }
        if (failed) return null
        return try {
            PDFBoxResourceLoader.init(context.applicationContext)

            // PDFBox needs a real file, so copy to cache once and reuse it
            val file = File(context.cacheDir, "pdf_${uri.toString().hashCode()}.pdf")
            if (!file.exists() || file.length() == 0L) {
                val tmp = File(context.cacheDir, file.name + ".tmp")
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                tmp.renameTo(file)
            }
            // Temp-file mode keeps document data on disk instead of in memory
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).also { doc = it }
        } catch (e: Exception) {
            failed = true // encrypted, damaged or unreadable: behave as "no text"
            null
        }
    }

    suspend fun pageText(index: Int): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            cache.get(index)?.let { return@withContext it }
            val d = open() ?: return@withContext ""
            val text = try {
                val stripper = PDFTextStripper()
                stripper.setStartPage(index + 1) // PDFBox pages start at 1
                stripper.setEndPage(index + 1)
                stripper.setSortByPosition(true)
                stripper.getText(d).trim()
            } catch (e: Exception) {
                ""
            }
            cache.put(index, text)
            text
        }
    }

    override fun close() {
        try { doc?.close() } catch (e: Exception) { }
        doc = null
    }
}