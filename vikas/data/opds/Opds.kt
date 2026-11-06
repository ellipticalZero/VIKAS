package com.example.vikas.data.opds

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.vikas.data.db.AppDb
import com.example.vikas.data.db.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Acquisition(val url: String, val format: String)

data class OpdsEntry(
    val title: String,
    val author: String?,
    val summary: String?,
    val navUrl: String?,                 // opens a sub-catalog, or a book's full page
    val acquisitions: List<Acquisition>  // download links in formats the app can read
)

data class OpdsFeed(
    val url: String,
    val title: String,
    val entries: List<OpdsEntry>,
    val nextUrl: String?,
    val searchTemplate: String?,
    val searchDescUrl: String?
)

class Auth(val host: String, val header: String)

sealed interface Loaded {
    class Catalog(val feed: OpdsFeed) : Loaded
    class Book(val url: String, val format: String) : Loaded
}

sealed interface AddResult {
    object Added : AddResult
    object AlreadyThere : AddResult
}

class OnlineException(message: String) : Exception(message)

// Which of our formats a link is, from its MIME type or file name. Null = not supported.
fun formatOf(type: String?, url: String): String? {
    val t = (type ?: "").lowercase()
    val path = url.substringBefore('?').lowercase()
    return when {
        "epub" in t || path.endsWith(".epub") || path.contains(".epub.") -> "epub"
        "pdf" in t || path.endsWith(".pdf") -> "pdf"
        "cbz" in t || "comicbook" in t || path.endsWith(".cbz") -> "cbz"
        t.startsWith("text/plain") || path.endsWith(".txt") -> "txt"
        else -> null
    }
}

object OpdsParser {
    private val ORDER = listOf("epub", "pdf", "cbz", "txt")

    fun abs(base: String, href: String): String =
        try { URL(URL(base), href.trim()).toString() } catch (e: Exception) { href }

    private fun Element.firstChild(name: String): Element? =
        children().firstOrNull { it.tagName() == name }

    private fun Element.childLinks(): List<Element> =
        children().filter { it.tagName() == "link" }

    private fun clean(text: String?): String? =
        text?.let { Jsoup.parse(it).text().trim() }?.takeIf { it.isNotEmpty() }

    // A direct link to a book file is shown as a catalog page with one book on it
    fun singleBook(url: String, format: String): OpdsFeed {
        val name = Uri.decode(url.substringBefore('?').substringAfterLast('/'))
            .substringBeforeLast('.').ifBlank { "Book" }
        return OpdsFeed(
            url, "Book file",
            listOf(OpdsEntry(name, null, null, null, listOf(Acquisition(url, format)))),
            null, null, null
        )
    }

    fun parse(xml: String, baseUrl: String): OpdsFeed {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val feedEl = doc.selectFirst("feed")
        val entryEls: List<Element>
        val title: String
        val feedLinks: List<Element>
        if (feedEl != null) {
            entryEls = feedEl.children().filter { it.tagName() == "entry" }
            title = feedEl.firstChild("title")?.text().orEmpty()
            feedLinks = feedEl.childLinks()
        } else {
            // A single <entry> document: the full page of one book
            val e = doc.selectFirst("entry")
                ?: throw OnlineException("This address is not an OPDS catalog (no feed was found).")
            entryEls = listOf(e)
            title = e.firstChild("title")?.text().orEmpty()
            feedLinks = emptyList()
        }

        var next: String? = null
        var template: String? = null
        var desc: String? = null
        for (l in feedLinks) {
            val rel = l.attr("rel")
            val type = l.attr("type")
            val href = l.attr("href")
            if (href.isBlank()) continue
            when {
                rel == "next" -> next = abs(baseUrl, href)
                rel == "search" && type.contains("opensearchdescription") -> desc = abs(baseUrl, href)
                rel == "search" && href.contains("{searchTerms}") -> template = href
            }
        }
        return OpdsFeed(
            url = baseUrl,
            title = title.ifBlank { "Catalog" },
            entries = entryEls.map { entry(it, baseUrl) },
            nextUrl = next,
            searchTemplate = template,
            searchDescUrl = desc
        )
    }

    private fun entry(e: Element, base: String): OpdsEntry {
        val links = e.childLinks()

        // Download links, one per format we can read, best format first
        val acquisitions = links
            .filter { it.attr("rel").startsWith("http://opds-spec.org/acquisition") }
            .mapNotNull { l ->
                val href = abs(base, l.attr("href"))
                formatOf(l.attr("type"), href)?.let { Acquisition(href, it) }
            }
            .distinctBy { it.format }
            .sortedBy { ORDER.indexOf(it.format) }

        // No download link here: a sub-catalog, or a full-entry page that has the download links
        val nav = if (acquisitions.isNotEmpty()) null else links
            .firstOrNull {
                it.attr("type").contains("atom+xml") &&
                        it.attr("rel") != "self" && it.attr("href").isNotBlank()
            }?.let { abs(base, it.attr("href")) }

        val author = e.firstChild("author")?.firstChild("name")?.text()?.takeIf { it.isNotBlank() }
        val summary = clean((e.firstChild("summary") ?: e.firstChild("content"))?.text())?.take(300)

        return OpdsEntry(
            title = e.firstChild("title")?.text().orEmpty().ifBlank { "Untitled" },
            author = author,
            summary = summary,
            navUrl = nav,
            acquisitions = acquisitions
        )
    }
}

class OpdsClient(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AppDb.get(appContext).bookDao()

    private companion object {
        const val UA = "VIKAS-EbookReader/1.0 (Android; student project)"
        const val ACCEPT_FEED =
            "application/atom+xml, application/opds+json;q=0.1, application/xml;q=0.9, */*;q=0.5"
        const val MAX_BYTES = 400L * 1024 * 1024
    }

    // Adds https:// if missing and pulls user:password out of the address
    fun prepare(rawUrl: String): Pair<String, Auth?> {
        var url = rawUrl.trim()
        if (!url.contains("://")) url = "https://$url"
        val u = try { URL(url) } catch (e: Exception) {
            throw OnlineException("That is not a valid web address.")
        }
        if (u.protocol != "http" && u.protocol != "https") {
            throw OnlineException("Only http and https addresses are supported.")
        }
        val info = u.userInfo ?: return url to null
        val clean = url.replaceFirst("$info@", "")
        val creds = info.split(":", limit = 2).joinToString(":") { Uri.decode(it) }
        val header = "Basic " + Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
        return clean to Auth(u.host, header)
    }

    // Opens a connection, following redirects ourselves (HttpURLConnection will not
    // follow a redirect from http to https). Credentials only go to the original host.
    private fun open(url: String, auth: Auth?, accept: String): HttpURLConnection {
        var current = url
        for (i in 0 until 6) {
            val u = URL(current)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", accept)
                if (auth != null && u.host == auth.host) setRequestProperty("Authorization", auth.header)
            }
            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw OnlineException("The server sent a bad redirect.")
                current = URL(u, location).toString()
            } else {
                return conn
            }
        }
        throw OnlineException("The server redirected too many times.")
    }

    private fun check(conn: HttpURLConnection) {
        when (val c = conn.responseCode) {
            in 200..299 -> {}
            401, 403 -> throw OnlineException(
                "This catalog needs a username and password. Write the address like " +
                        "https://user:password@host/opds"
            )
            404 -> throw OnlineException("Nothing was found at that address (error 404).")
            else -> throw OnlineException("The server returned error $c.")
        }
    }

    private fun readLimited(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw OnlineException("The server's response is too large.")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    // Decides what an address is: a catalog page, or a book file
    suspend fun load(url: String, auth: Auth?): Loaded = withContext(Dispatchers.IO) {
        try {
            val conn = open(url, auth, ACCEPT_FEED)
            try {
                check(conn)
                val finalUrl = conn.url.toString()
                val type = conn.contentType?.lowercase().orEmpty()
                val bookFormat = formatOf(type, finalUrl)
                when {
                    "xml" in type || "opds" in type -> {
                        val body = conn.inputStream.use { readLimited(it, 8_000_000) }
                        Loaded.Catalog(OpdsParser.parse(body, finalUrl))
                    }
                    bookFormat != null -> Loaded.Book(finalUrl, bookFormat)
                    "html" in type -> throw OnlineException(
                        "That address is a web page, not an OPDS catalog or a book file. " +
                                "Catalog addresses often end in /opds."
                    )
                    else -> throw OnlineException(
                        "This address is not an OPDS catalog or a supported book file."
                    )
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            throw OnlineException("Could not connect. Check your internet connection and the address.")
        }
    }

    // Builds the address for a search, from the catalog's search template
    suspend fun searchUrl(feed: OpdsFeed, query: String, auth: Auth?): String? =
        withContext(Dispatchers.IO) {
            var template = feed.searchTemplate
            if (template == null && feed.searchDescUrl != null) {
                try {
                    val conn = open(feed.searchDescUrl, auth, "application/opensearchdescription+xml, */*")
                    try {
                        check(conn)
                        val xml = conn.inputStream.use { readLimited(it, 1_000_000) }
                        val urls = Jsoup.parse(xml, "", Parser.xmlParser()).select("Url")
                        template = (urls.firstOrNull { it.attr("type").contains("atom+xml") }
                            ?: urls.firstOrNull())?.attr("template")
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    return@withContext null
                }
            }
            template?.takeIf { it.isNotBlank() }?.let {
                val filled = it
                    .replace("{searchTerms}", URLEncoder.encode(query, "UTF-8"))
                    .replace(Regex("\\{[^}]*\\}"), "") // drop optional OpenSearch fields
                OpdsParser.abs(feed.url, filled)
            }
        }

    // Downloads a book into the app's private storage and adds it to the library
    suspend fun download(
        url: String,
        format: String,
        title: String,
        author: String?,
        auth: Auth?,
        onProgress: (done: Long, total: Long) -> Unit
    ): AddResult = withContext(Dispatchers.IO) {
        try {
            val dir = File(appContext.filesDir, "books").apply { mkdirs() }
            val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
                .ifEmpty { "book" }
            // Same address -> same file name, so a second download never makes a duplicate
            val dest = File(dir, "$slug-${Integer.toHexString(url.hashCode())}.$format")
            val uri = Uri.fromFile(dest).toString()
            val existed = dao.exists(uri) > 0

            if (!dest.exists() || dest.length() == 0L) {
                fetchToFile(url, auth, format, dest, onProgress)
            }
            dao.insertAll(
                listOf(
                    BookEntity(
                        uri = uri,
                        title = title.ifBlank { "Untitled" },
                        author = author,
                        format = format,
                        coverPath = null,
                        addedAt = System.currentTimeMillis()
                    )
                )
            )
            dao.unhide(uri) // downloading again restores a book that was removed
            if (existed) AddResult.AlreadyThere else AddResult.Added
        } catch (e: IOException) {
            throw OnlineException("The download failed. Check your connection and try again.")
        }
    }

    private suspend fun fetchToFile(
        url: String,
        auth: Auth?,
        format: String,
        dest: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val part = File(dest.path + ".part")
        val conn = open(url, auth, "*/*")
        try {
            check(conn)
            val total = conn.contentLengthLong
            if (total > MAX_BYTES) throw OnlineException("That file is too large (over 400 MB).")
            var done = 0L
            var reported = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive() // stop at once if cancelled
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > MAX_BYTES) throw OnlineException("That file is too large (over 400 MB).")
                        if (done - reported >= 128 * 1024) {
                            reported = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            verify(part, format)
            if (!part.renameTo(dest)) throw IOException("Could not save the file")
        } catch (e: Exception) {
            part.delete() // never leave half a download behind
            throw e
        } finally {
            conn.disconnect()
        }
    }

    // Make sure we really got a book, not a login page or an error page
    private fun verify(file: File, format: String) {
        val head = file.inputStream().use { input ->
            val b = ByteArray(1024)
            val n = input.read(b)
            if (n <= 0) ByteArray(0) else b.copyOf(n)
        }
        val ok = when (format) {
            "epub", "cbz" -> head.size >= 4 &&
                    head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            "pdf" -> String(head, Charsets.ISO_8859_1).contains("%PDF")
            else -> !String(head, Charsets.ISO_8859_1).lowercase().contains("<html")
        }
        if (!ok) {
            throw OnlineException(
                "The downloaded file is not a valid ${format.uppercase()} file. " +
                        "The site may need a login, or the link may not be a direct download."
            )
        }
    }
}