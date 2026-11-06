package com.example.vikas.data.readers

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

class EpubBook private constructor(
    private val zip: ZipFile,
    val chapters: List<String>,              // full zip paths, in reading order
    private val mediaTypes: Map<String, String>,
    val title: String?,
    val author: String?,
    val coverPath: String?
) : Closeable {

    val chapterCount: Int get() = chapters.size

    fun readText(path: String): String? {
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    // Returns (mime type, stream) for any file inside the EPUB, or null if missing
    fun openResource(path: String): Pair<String, InputStream>? {
        val entry = zip.getEntry(path) ?: return null
        val ext = path.substringAfterLast('.', "").lowercase()
        val mime = mediaTypes[path]?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        return mime to zip.getInputStream(entry)
    }

    override fun close() { zip.close() }

    companion object {
        fun open(context: Context, uri: Uri): EpubBook {
            // ZipFile needs a real file for random access, so copy to cache once
            val cached = File(context.cacheDir, "epub_${uri.toString().hashCode()}.epub")
            if (!cached.exists() || cached.length() == 0L) {
                val tmp = File(context.cacheDir, cached.name + ".tmp")
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                tmp.renameTo(cached)
            }
            val zip = ZipFile(cached)
            try {
                return parse(zip)
            } catch (e: Exception) {
                zip.close()
                throw e
            }
        }

        private fun readEntry(zip: ZipFile, path: String): String? {
            val entry = zip.getEntry(path) ?: return null
            return zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }

        private fun parse(zip: ZipFile): EpubBook {
            // 1. container.xml points to the package (.opf) file
            val container = readEntry(zip, "META-INF/container.xml")
                ?: error("Not a valid EPUB (no container.xml)")
            val opfPath = Jsoup.parse(container, "", Parser.xmlParser())
                .selectFirst("rootfile")?.attr("full-path")
                ?: error("EPUB has no package file")

            // 2. The .opf holds metadata, the manifest and the spine (reading order)
            val opf = Jsoup.parse(
                readEntry(zip, opfPath) ?: error("EPUB package file is missing"),
                "", Parser.xmlParser()
            )
            val opfDir = opfPath.substringBeforeLast('/', "")

            val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()
            val author = opf.getElementsByTag("dc:creator").firstOrNull()?.text()

            class Item(val path: String, val type: String, val props: String)
            val manifest = opf.select("manifest > item").associate {
                it.attr("id") to Item(
                    resolve(opfDir, Uri.decode(it.attr("href"))),
                    it.attr("media-type"),
                    it.attr("properties")
                )
            }
            val mediaTypes = manifest.values.associate { it.path to it.type }

            // 3. Chapters = spine items, skipping the navigation (table of contents) page
            val chapters = opf.select("spine > itemref")
                .mapNotNull { manifest[it.attr("idref")] }
                .filter { !it.props.split(' ').contains("nav") }
                .map { it.path }
            if (chapters.isEmpty()) error("No chapters found in this EPUB")

            val metaCoverId = opf.select("metadata > meta[name=cover]")
                .firstOrNull()?.attr("content")
            val coverPath = manifest.values
                .firstOrNull { it.props.split(' ').contains("cover-image") }?.path
                ?: metaCoverId?.let { id -> manifest[id]?.takeIf { it.type.startsWith("image/") }?.path }
                ?: firstImageOf(zip, chapters.first())

            return EpubBook(zip, chapters, mediaTypes, title, author, coverPath)
        }

        // Resolves "../" and "./" relative to a base folder inside the zip
        private fun resolve(base: String, rel: String): String {
            val parts = ArrayDeque<String>()
            if (base.isNotEmpty()) parts.addAll(base.split('/'))
            for (seg in rel.substringBefore('#').split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeLast()
                    else -> parts.addLast(seg)
                }
            }
            return parts.joinToString("/")
        }

        private fun firstImageOf(zip: ZipFile, chapterPath: String): String? {
            val html = readEntry(zip, chapterPath) ?: return null
            val doc = Jsoup.parse(html)
            val src = doc.selectFirst("img[src]")?.attr("src")
                ?: doc.selectFirst("image")?.attr("xlink:href")
                ?: return null
            val path = resolve(chapterPath.substringBeforeLast('/', ""), Uri.decode(src))
            return path.takeIf { zip.getEntry(it) != null }
        }

    }
}