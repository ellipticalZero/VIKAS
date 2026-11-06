package com.example.vikas.data.readers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

data class SearchHit(val page: Int, val snippet: String)

interface Searchable {
    suspend fun search(query: String): List<SearchHit>
}

object TextSearch {
    const val MAX_HITS = 200

    // Case-insensitive scan; pageOf maps a character offset to a page number
    fun findAll(text: String, query: String, pageOf: (Int) -> Int): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        var from = 0
        while (out.size < MAX_HITS) {
            val i = text.indexOf(query, from, ignoreCase = true)
            if (i < 0) break
            out += SearchHit(pageOf(i), snippet(text, i, query.length))
            from = i + query.length
        }
        return out
    }

    private fun snippet(text: String, start: Int, len: Int): String {
        val s = (start - 40).coerceAtLeast(0)
        val e = (start + len + 40).coerceAtMost(text.length)
        val body = text.substring(s, e).replace(Regex("\\s+"), " ").trim()
        return (if (s > 0) "…" else "") + body + (if (e < text.length) "…" else "")
    }
}

// EPUB: each chapter is one "page", so a hit's page is its chapter index
class EpubSearcher(private val book: EpubBook) : Searchable {
    override suspend fun search(query: String): List<SearchHit> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<SearchHit>()
            for ((i, path) in book.chapters.withIndex()) {
                if (out.size >= TextSearch.MAX_HITS) break
                val html = book.readText(path) ?: continue
                val text = Jsoup.parse(html).body().text()
                out += TextSearch.findAll(text, query) { i }
            }
            out.take(TextSearch.MAX_HITS)
        }
}