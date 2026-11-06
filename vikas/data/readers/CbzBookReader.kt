package com.example.vikas.data.readers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class CbzBookReader(context: Context, uri: Uri) : BookReader {

    private val zip: ZipFile
    private val entries: List<ZipEntry>

    init {
        // ZipFile needs a real file for random access, so copy to cache once
        val cached = File(context.cacheDir, "cbz_${uri.toString().hashCode()}.cbz")
        if (!cached.exists() || cached.length() == 0L) {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                cached.outputStream().use { input.copyTo(it) }
            }
        }
        zip = ZipFile(cached)
        val imageRegex = Regex(".*\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)
        entries = zip.entries().asSequence()
            .filter { !it.isDirectory }
            .filter { imageRegex.matches(it.name) }
            .filter { !it.name.startsWith("__MACOSX") && !it.name.substringAfterLast('/').startsWith(".") }
            .sortedBy { e -> e.name.replace(Regex("\\d+")) { it.value.padStart(10, '0') } }
            .toList()
    }

    override val pageCount: Int get() = entries.size

    override suspend fun renderPage(index: Int, targetWidth: Int): Bitmap =
        withContext(Dispatchers.IO) {
            val entry = entries[index]

            // Pass 1: read only the image size
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }

            // Pass 2: decode scaled down to roughly the screen width
            val opts = BitmapFactory.Options().apply {
                inSampleSize = (bounds.outWidth / targetWidth).coerceAtLeast(1)
            }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("Could not decode page ${index + 1}")
        }

    override fun close() { zip.close() }
}