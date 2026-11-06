package com.example.vikas.data.readers

import android.content.Context
import android.net.Uri
import com.example.vikas.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BookReaders {
    val supportedFormats = setOf("pdf", "cbz", "txt", "epub")
    suspend fun open(context: Context, book: BookEntity): BookReader =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(book.uri)
            when (book.format) {
                "pdf" -> PdfBookReader(context, uri)
                "cbz" -> CbzBookReader(context, uri)
                "txt" -> TxtBookReader(context, uri)
                else -> error("Unsupported format: ${book.format}")
            }
        }
}