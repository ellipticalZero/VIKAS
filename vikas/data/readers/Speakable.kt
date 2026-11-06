package com.example.vikas.data.readers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

// A reader that can provide the plain text of a page, for read aloud
interface Speakable {
    suspend fun pageText(page: Int): String
}

// EPUB: one "page" is one chapter
class EpubSpeaker(private val book: EpubBook) : Speakable {
    override suspend fun pageText(page: Int): String = withContext(Dispatchers.IO) {
        val html = book.readText(book.chapters[page]) ?: return@withContext ""
        val body = Jsoup.parse(html).body()
        val blocks = body.select("h1, h2, h3, h4, h5, h6, p, li")
            .map { it.text() }
            .filter { it.isNotBlank() }
        if (blocks.isEmpty()) body.text() else blocks.joinToString("\n")
    }
}