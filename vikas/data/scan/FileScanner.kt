package com.example.vikas.data.scan

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.vikas.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileScanner(private val context: Context) {

    private val supported = setOf("epub", "pdf", "cbz", "txt")

    suspend fun scan(treeUri: Uri): List<BookEntity> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext emptyList()
        val result = mutableListOf<BookEntity>()
        walk(root, result)
        result
    }

    private fun walk(dir: DocumentFile, out: MutableList<BookEntity>) {
        for (file in dir.listFiles()) {
            if (file.isDirectory) {
                walk(file, out)
            } else {
                val name = file.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in supported) {
                    out += BookEntity(
                        uri = file.uri.toString(),
                        title = name.substringBeforeLast('.'),
                        author = null,
                        format = ext,
                        coverPath = null,
                        addedAt = System.currentTimeMillis()
                    )
                }
            }
        }
    }
}