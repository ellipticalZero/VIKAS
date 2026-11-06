package com.example.vikas.data.readers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class PagedTextReader(private val text: String) : BookReader, Searchable, Speakable {

    companion object {
        const val PAGE_W = 1080
        const val PAGE_H = 1700
        const val MARGIN = 64
    }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 44f
    }
    private val layout: StaticLayout
    private val pageStartLines: List<Int>

    init {
        layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, PAGE_W - 2 * MARGIN)
            .setLineSpacing(0f, 1.2f)
            .build()

        // Split the lines into pages that fit the page height
        val usable = PAGE_H - 2 * MARGIN
        val starts = mutableListOf(0)
        var pageTop = layout.getLineTop(0)
        for (line in 0 until layout.lineCount) {
            if (layout.getLineBottom(line) - pageTop > usable) {
                starts += line
                pageTop = layout.getLineTop(line)
            }
        }
        pageStartLines = starts
    }

    override val pageCount: Int get() = pageStartLines.size

    override suspend fun renderPage(index: Int, targetWidth: Int): Bitmap =
        withContext(Dispatchers.Default) {
            val top = layout.getLineTop(pageStartLines[index])
            val bmp = Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val canvas = Canvas(bmp)
            canvas.clipRect(
                MARGIN.toFloat(), MARGIN.toFloat(),
                (PAGE_W - MARGIN).toFloat(), (PAGE_H - MARGIN).toFloat()
            )
            canvas.translate(MARGIN.toFloat(), (MARGIN - top).toFloat())
            layout.draw(canvas)
            bmp
        }

    // Character offset where each page starts, so a position can be mapped to a page
    private val pageStartOffsets: List<Int> by lazy {
        pageStartLines.map { layout.getLineStart(it) }
    }

    fun pageOfOffset(offset: Int): Int {
        val i = pageStartOffsets.binarySearch(offset) // O(log pages)
        return if (i >= 0) i else (-i - 2).coerceAtLeast(0)
    }

    fun pageStartOf(page: Int): Int = pageStartOffsets[page]

    fun pageEndOf(page: Int): Int =
        if (page + 1 < pageStartOffsets.size) pageStartOffsets[page + 1] else text.length

    override suspend fun search(query: String): List<SearchHit> =
        withContext(Dispatchers.Default) {
            TextSearch.findAll(text, query) { offset -> pageOfOffset(offset) }
        }

    override suspend fun pageText(page: Int): String {
        val start = pageStartOffsets[page]
        val end = if (page + 1 < pageStartOffsets.size) pageStartOffsets[page + 1] else text.length
        return text.substring(start, end)
    }

    // ----- Support for highlights -----

    // The character under a point on the page image (bx, by are in page-image pixels)
    fun offsetAt(page: Int, bx: Float, by: Float): Int {
        val first = pageStartLines[page]
        val last = (pageStartLines.getOrNull(page + 1) ?: layout.lineCount) - 1
        val top = layout.getLineTop(first)
        val y = (by - MARGIN + top).toInt()
            .coerceIn(layout.getLineTop(first), layout.getLineBottom(last) - 1)
        val line = layout.getLineForVertical(y).coerceIn(first, last)
        val x = (bx - MARGIN).coerceIn(0f, (PAGE_W - 2 * MARGIN).toFloat())
        return layout.getOffsetForHorizontal(line, x)
    }

    // The word around a character position
    fun wordRange(offset: Int): IntRange {
        if (text.isEmpty()) return 0..0
        val o = offset.coerceIn(0, text.length - 1)
        if (!text[o].isLetterOrDigit()) return o..o
        var s = o
        var e = o
        while (s > 0 && text[s - 1].isLetterOrDigit()) s--
        while (e + 1 < text.length && text[e + 1].isLetterOrDigit()) e++
        return s..e
    }

    fun snippet(start: Int, end: Int): String =
        text.substring(start.coerceAtLeast(0), end.coerceAtMost(text.length))

    // Shape of a character range, in page-image pixels (what the highlight overlay draws)
    fun selectionPath(page: Int, start: Int, end: Int): Path {
        val path = Path()
        layout.getSelectionPath(start, end, path)
        val top = layout.getLineTop(pageStartLines[page])
        path.offset(MARGIN.toFloat(), (MARGIN - top).toFloat())
        return path
    }

    override fun close() {}
}