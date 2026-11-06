package com.example.vikas.ui.reader

import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.vikas.data.db.HighlightEntity
import com.example.vikas.data.readers.EpubBook
import com.example.vikas.data.settings.AppTheme
import com.example.vikas.data.settings.LocalReaderStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.math.abs

private const val BASE = "https://epub.local/"

// Stops scripts that are inside the book from running. Our own helper script is not
// affected, because the app injects it directly. If highlights do not appear at all,
// set this to false to test.
private const val BLOCK_BOOK_SCRIPTS = true

private fun extraHead(theme: AppTheme): String {
    val themeCss = when (theme) {
        AppTheme.DARK -> """
  html, body { background: #000000 !important; }
  html body, html body * { background-color: transparent !important; color: #E0E0E0 !important; }
  a { color: #8AB4F8 !important; }"""
        AppTheme.SEPIA -> """
  html, body { background: #F4ECD8 !important; }
  html body, html body * { background-color: transparent !important; color: #5B4636 !important; }"""
        else -> ""
    }
    return """
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html body { margin: 0 !important; padding: 16px 5% 24px 5% !important; box-sizing: border-box !important; }
  html body p { line-height: 1.55 !important; }
  img, svg { max-width: 100% !important; }
  mark[data-vk] { color: inherit; }
$themeCss
</style>
"""
}

private fun injectHead(html: String, theme: AppTheme): String {
    val head = extraHead(theme)
    val i = html.indexOf("</head>", ignoreCase = true)
    return if (i >= 0) html.substring(0, i) + head + html.substring(i) else head + html
}

private fun webBackground(theme: AppTheme): Int = when (theme) {
    AppTheme.DARK -> Color.BLACK
    AppTheme.SEPIA -> 0xFFF4ECD8.toInt()
    else -> Color.WHITE
}

// Only ids, positions and colours go into the page, never the book text
private fun highlightsJson(list: List<HighlightEntity>, dark: Boolean): String =
    list.joinToString(",", "[", "]") { h ->
        """{"id":${h.id},"s":${h.startOffset},"e":${h.endOffset},"c":"${HighlightColors.css(h.color, dark)}"}"""
    }

// Holds the chapter currently shown, so the WebView client can serve it
private class ChapterState {
    @Volatile var path: String = ""
    @Volatile var html: String = ""
}

@Composable
fun EpubChapterView(
    book: EpubBook,
    chapter: Int,
    modifier: Modifier = Modifier,
    highlights: List<HighlightEntity> = emptyList(),
    onTap: () -> Unit = {},
    onHighlightSelected: (start: Int, end: Int, text: String) -> Unit = { _, _, _ -> },
    onHighlightTapped: (id: Long) -> Unit = {}
) {
    val style = LocalReaderStyle.current

    val html by produceState<String?>(null, chapter, style.theme) {
        value = withContext(Dispatchers.IO) {
            book.readText(book.chapters[chapter])?.let { injectHead(it, style.theme) }
                ?: "<p>Could not load this chapter.</p>"
        }
    }

    val h = html
    if (h == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val state = remember { ChapterState() }
    val chapterPath = book.chapters[chapter]
    val currentOnTap by rememberUpdatedState(onTap)
    val currentSelected by rememberUpdatedState(onHighlightSelected)
    val currentTapped by rememberUpdatedState(onHighlightTapped)
    val hlJson = highlightsJson(highlights, style.theme == AppTheme.DARK)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            ReaderWebView(ctx).apply {
                // JavaScript is on only for our helper; the book's own scripts are blocked
                // by the Content-Security-Policy header added below
                settings.javaScriptEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.allowFileAccess = false
                settings.allowContentAccess = false

                // Decide scroll direction early so vertical scrolling never turns the page.
                // A tap either opens a highlight under the finger or toggles the controls.
                val slop = ViewConfiguration.get(ctx).scaledTouchSlop
                var startX = 0f
                var startY = 0f
                var decided = false
                var downTime = 0L
                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = ev.x
                            startY = ev.y
                            decided = false
                            downTime = SystemClock.uptimeMillis()
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_MOVE -> if (!decided) {
                            val dx = abs(ev.x - startX)
                            val dy = abs(ev.y - startY)
                            if (dx > slop / 2f || dy > slop / 2f) {
                                decided = true
                                val horizontal = dx > dy * 1.7f
                                v.parent?.requestDisallowInterceptTouchEvent(!horizontal)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            val moved = abs(ev.x - startX) > slop || abs(ev.y - startY) > slop
                            if (!moved && SystemClock.uptimeMillis() - downTime < 300) {
                                val d = v.resources.displayMetrics.density
                                val x = ev.x / d
                                val y = ev.y / d
                                (v as ReaderWebView).runJs(
                                    "window.vikas ? vikas.hit($x, $y) : -1"
                                ) { r ->
                                    val id = r.toLongOrNull() ?: -1L
                                    if (id >= 0) currentTapped(id) else currentOnTap()
                                }
                            }
                        }
                    }
                    false // let the WebView handle the touch as normal
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        (view as? ReaderWebView)?.onPageLoaded()
                    }

                    override fun shouldInterceptRequest(
                        view: WebView, request: WebResourceRequest
                    ): WebResourceResponse? {
                        val url = request.url
                        if (url.host != "epub.local") return notFound()
                        val path = (url.path ?: "").removePrefix("/")

                        // The chapter page itself
                        if (path == state.path) {
                            val headers = if (BLOCK_BOOK_SCRIPTS) {
                                mapOf("Content-Security-Policy" to "script-src 'none'; object-src 'none'")
                            } else emptyMap()
                            return WebResourceResponse(
                                "text/html", "UTF-8", 200, "OK", headers,
                                ByteArrayInputStream(state.html.toByteArray(Charsets.UTF_8))
                            )
                        }
                        // Images, CSS, fonts: read straight from the EPUB zip on demand
                        book.openResource(path)?.let { (mime, stream) ->
                            return WebResourceResponse(mime, null, stream)
                        }
                        return notFound()
                    }
                }
            }
        },
        update = { web ->
            // Text size changes apply instantly, without reloading
            web.settings.textZoom = style.fontPercent
            web.onHighlightSelected = { s, e, t -> currentSelected(s, e, t) }
            // A new HTML string (first load, or after a theme change) triggers a reload
            if (state.html !== h) {
                state.path = chapterPath
                state.html = h
                web.pageReady = false
                web.setBackgroundColor(webBackground(style.theme))
                web.loadUrl(BASE + Uri.encode(chapterPath, "/"))
            }
            web.syncHighlights(hlJson)
        },
        onRelease = { it.destroy() }
    )
}

private fun notFound() = WebResourceResponse(
    "text/plain", "utf-8", 404, "Not Found",
    emptyMap(), ByteArrayInputStream(ByteArray(0))
)