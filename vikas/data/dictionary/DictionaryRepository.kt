package com.example.vikas.data.dictionary

import android.content.Context
import com.example.vikas.data.db.AppDb
import com.example.vikas.data.db.DictionaryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Definition(val text: String, val example: String?)
data class Meaning(val partOfSpeech: String, val definitions: List<Definition>)
data class WordEntry(val word: String, val phonetic: String?, val meanings: List<Meaning>)

sealed interface LookupResult {
    data class Found(val entry: WordEntry, val fromCache: Boolean, val note: String?) : LookupResult
    data class NotFound(val word: String) : LookupResult
    data class Offline(val word: String) : LookupResult
}

class DictionaryRepository(context: Context) {

    private val dao = AppDb.get(context).dictionaryDao()

    private sealed interface Fetch {
        class Ok(val json: String) : Fetch
        object Missing : Fetch   // the service answered: this word is not in its data
        object Failed : Fetch    // timeout, no connection, throttled, or server error
    }

    suspend fun lookup(input: String): LookupResult = withContext(Dispatchers.IO) {
        val word = normalize(input)
        if (word.isEmpty()) return@withContext LookupResult.NotFound(input.trim())

        var offline = false
        for (candidate in candidates(word)) {
            // 1. Local cache first (primary-key index lookup)
            dao.get(candidate)?.let { hit ->
                runCatching { parse(hit.json) }.getOrNull()?.let { entry ->
                    return@withContext LookupResult.Found(entry, true, noteFor(word, candidate))
                }
            }
            if (offline) continue // services unreachable: remaining candidates use the cache only

            // 2. Network: primary service, then the fallback
            when (val r = fetchAny(candidate)) {
                is Fetch.Ok -> {
                    val entry = runCatching { parse(r.json) }.getOrNull() ?: continue
                    val now = System.currentTimeMillis()
                    dao.put(DictionaryEntity(candidate, r.json, now))
                    if (candidate != word) dao.put(DictionaryEntity(word, r.json, now))
                    return@withContext LookupResult.Found(entry, false, noteFor(word, candidate))
                }
                Fetch.Missing -> {}
                Fetch.Failed -> offline = true
            }
        }
        if (offline) LookupResult.Offline(word) else LookupResult.NotFound(word)
    }

    private fun noteFor(typed: String, found: String): String? =
        if (typed != found) "Showing the result for \"$found\"" else null

    // Lowercase, first word only, letters / apostrophes / hyphens only, no possessive
    private fun normalize(input: String): String {
        val first = input.trim().lowercase().split(Regex("\\s+")).firstOrNull() ?: return ""
        return first
            .replace(Regex("[^\\p{L}'-]"), "")
            .removeSuffix("'s")
            .trim('\'', '-')
    }

    // The word itself first, then simple suffix-stripped forms
    private fun candidates(w: String): List<String> {
        val out = linkedSetOf(w)
        for (suffix in listOf("ing", "ed", "es", "s", "ly")) {
            if (w.endsWith(suffix) && w.length - suffix.length >= 3) {
                val base = w.dropLast(suffix.length)
                out += base                                                        // walked -> walk
                out += base + "e"                                                  // making -> make
                if (base.last() == base[base.length - 2]) out += base.dropLast(1)  // running -> run
                if (base.endsWith("i")) out += base.dropLast(1) + "y"              // studies -> study
            }
        }
        return out.toList()
    }

    // ---------- Network sources ----------

    // Tries each source in order. A word is "Missing" only if every source answered
    // and none had it; if any source failed and none found the word, the result is "Failed".
    private suspend fun fetchAny(word: String): Fetch {
        var failed = false
        val sources = listOf<(String) -> Fetch>(
            { fromDictionaryApi(it) },
            { fromWiktionary(it) }
        )
        for (source in sources) {
            when (val r = withRetry { source(word) }) {
                is Fetch.Ok -> if (runCatching { parse(r.json) }.isSuccess) return r
                Fetch.Missing -> {}
                Fetch.Failed -> failed = true
            }
        }
        return if (failed) Fetch.Failed else Fetch.Missing
    }

    // One retry after a short pause, for temporary failures only
    private suspend fun withRetry(block: () -> Fetch): Fetch {
        var r = block()
        if (r is Fetch.Failed) {
            delay(600)
            r = block()
        }
        return r
    }

    private fun httpGet(url: String): Pair<Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                // Wikimedia asks API clients to identify themselves
                setRequestProperty("User-Agent", "VIKAS-EbookReader/1.0 (student project; Android)")
            }
            val code = conn.responseCode
            val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
            code to body
        } catch (e: IOException) {
            -1 to null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fromDictionaryApi(word: String): Fetch {
        val (code, body) = httpGet(
            "https://api.dictionaryapi.dev/api/v2/entries/en/" + URLEncoder.encode(word, "UTF-8")
        )
        return when {
            code == 200 && body != null -> Fetch.Ok(body)
            code == 404 -> Fetch.Missing
            else -> Fetch.Failed
        }
    }

    private fun fromWiktionary(word: String): Fetch {
        val (code, body) = httpGet(
            "https://en.wiktionary.org/api/rest_v1/page/definition/" + URLEncoder.encode(word, "UTF-8")
        )
        if (code == 404) return Fetch.Missing
        if (code != 200 || body == null) return Fetch.Failed
        return try {
            convertWiktionary(word, body)?.let { Fetch.Ok(it) } ?: Fetch.Missing
        } catch (e: Exception) {
            Fetch.Failed
        }
    }

    // Wiktionary returns {"en": [{partOfSpeech, definitions:[{definition (HTML), examples}]}], ...}.
    // We keep the English entries and rewrite them in the format we already store and parse.
    private fun convertWiktionary(word: String, body: String): String? {
        val en = JSONObject(body).optJSONArray("en") ?: return null
        val meanings = JSONArray()
        for (i in 0 until en.length()) {
            val item = en.getJSONObject(i)
            val defs = item.optJSONArray("definitions") ?: continue
            val outDefs = JSONArray()
            for (j in 0 until defs.length()) {
                val d = defs.getJSONObject(j)
                val text = Jsoup.parse(d.optString("definition")).text()
                if (text.isBlank()) continue
                val example = d.optJSONArray("examples")?.optString(0)
                    ?.let { Jsoup.parse(it).text() } ?: ""
                outDefs.put(JSONObject().put("definition", text).put("example", example))
            }
            if (outDefs.length() > 0) {
                meanings.put(
                    JSONObject()
                        .put("partOfSpeech", item.optString("partOfSpeech"))
                        .put("definitions", outDefs)
                )
            }
        }
        if (meanings.length() == 0) return null
        val entry = JSONObject().put("word", word).put("meanings", meanings)
        return JSONArray().put(entry).toString()
    }

    // ---------- Parsing (works for both sources, since both are stored in one format) ----------

    private fun parse(json: String): WordEntry {
        val arr = JSONArray(json)
        var word = ""
        var phonetic: String? = null
        val meanings = mutableListOf<Meaning>()

        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            if (word.isEmpty()) word = e.optString("word")
            if (phonetic == null) {
                phonetic = e.optString("phonetic").takeIf { it.isNotBlank() }
                    ?: e.optJSONArray("phonetics")?.let { ph ->
                        (0 until ph.length())
                            .map { ph.getJSONObject(it).optString("text") }
                            .firstOrNull { it.isNotBlank() }
                    }
            }
            val ms = e.optJSONArray("meanings") ?: continue
            for (j in 0 until ms.length()) {
                val m = ms.getJSONObject(j)
                val defs = m.optJSONArray("definitions") ?: continue
                val list = (0 until defs.length()).mapNotNull { k ->
                    val d = defs.getJSONObject(k)
                    val text = d.optString("definition")
                    if (text.isBlank()) null
                    else Definition(text, d.optString("example").takeIf { it.isNotBlank() })
                }
                if (list.isNotEmpty()) meanings += Meaning(m.optString("partOfSpeech"), list)
            }
        }
        if (meanings.isEmpty()) error("No meanings")
        return WordEntry(word, phonetic, meanings)
    }
}