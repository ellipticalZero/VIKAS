package com.example.vikas.data.readers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PdfBookReader(context: Context, uri: Uri) : BookReader, Searchable, Speakable {

    private val pfd = context.contentResolver.openFileDescriptor(uri, "r")!!
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex() // PdfRenderer allows only one open page at a time
    private val text = PdfTextExtractor(context, uri)

    override val pageCount: Int get() = renderer.pageCount

    override suspend fun renderPage(index: Int, targetWidth: Int): Bitmap =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                renderer.openPage(index).use { page ->
                    val height = (targetWidth.toFloat() * page.height / page.width).toInt()
                    val bmp = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }

    // Text extraction runs separately from rendering, so reading is never blocked by a search
    override suspend fun pageText(page: Int): String = text.pageText(page)

    override suspend fun search(query: String): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        for (p in 0 until pageCount) {
            if (out.size >= TextSearch.MAX_HITS) break
            out += TextSearch.findAll(text.pageText(p), query) { p }
        }
        return out.take(TextSearch.MAX_HITS)
    }

    override fun close() {
        renderer.close()
        pfd.close()
        text.close()
    }
}