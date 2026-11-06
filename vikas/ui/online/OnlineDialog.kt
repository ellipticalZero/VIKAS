package com.example.vikas.ui.online

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.vikas.data.opds.Acquisition
import com.example.vikas.data.opds.AddResult
import com.example.vikas.data.opds.Auth
import com.example.vikas.data.opds.Loaded
import com.example.vikas.data.opds.OnlineException
import com.example.vikas.data.opds.OpdsClient
import com.example.vikas.data.opds.OpdsEntry
import com.example.vikas.data.opds.OpdsFeed
import com.example.vikas.data.opds.OpdsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface Dl {
    class Running(val percent: Int) : Dl     // percent is -1 when the size is unknown
    class Done(val message: String) : Dl
    class Failed(val message: String) : Dl
}

private const val GUTENBERG = "https://m.gutenberg.org/ebooks.opds/"
private const val STANDARD_EBOOKS = "https://standardebooks.org/feeds/opds"

@Composable
fun OnlineDialog(onDismiss: () -> Unit, onAdded: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val client = remember { OpdsClient(context) }

    var input by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf<Auth?>(null) }
    val stack = remember { mutableStateListOf<OpdsFeed>() }   // catalog pages, newest last
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val downloads = remember { mutableStateMapOf<String, Dl>() }
    var searchText by remember { mutableStateOf("") }

    fun fail(e: Exception) {
        error = (e as? OnlineException)?.message ?: e.message ?: "Something went wrong."
    }

    fun navigate(url: String) {
        scope.launch {
            loading = true
            error = null
            try {
                when (val r = client.load(url, auth)) {
                    is Loaded.Catalog -> stack.add(r.feed)
                    is Loaded.Book -> stack.add(OpdsParser.singleBook(r.url, r.format))
                }
                searchText = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
            loading = false
        }
    }

    fun openAddress(raw: String) {
        if (raw.isBlank() || loading) return
        keyboard?.hide()
        focus.clearFocus()
        try {
            val (url, a) = client.prepare(raw)
            auth = a
            navigate(url)
        } catch (e: OnlineException) {
            error = e.message
        }
    }

    fun loadMore() {
        val feed = stack.lastOrNull() ?: return
        val next = feed.nextUrl ?: return
        if (loading) return
        scope.launch {
            loading = true
            error = null
            try {
                val r = client.load(next, auth)
                if (r is Loaded.Catalog && stack.lastOrNull() === feed) {
                    stack[stack.lastIndex] = feed.copy(
                        entries = feed.entries + r.feed.entries,
                        nextUrl = r.feed.nextUrl
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
            loading = false
        }
    }

    fun search(feed: OpdsFeed) {
        val q = searchText.trim()
        if (q.isEmpty() || loading) return
        keyboard?.hide()
        focus.clearFocus()
        scope.launch {
            loading = true
            error = null
            try {
                val url = client.searchUrl(feed, q, auth)
                    ?: throw OnlineException("This catalog does not support search.")
                val r = client.load(url, auth)
                if (r is Loaded.Catalog) {
                    stack.add(r.feed.copy(title = "Search: $q"))
                    searchText = ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
            loading = false
        }
    }

    fun download(entry: OpdsEntry, acq: Acquisition) {
        if (downloads[acq.url] is Dl.Running) return
        scope.launch {
            downloads[acq.url] = Dl.Running(-1)
            try {
                val result = client.download(acq.url, acq.format, entry.title, entry.author, auth) { done, total ->
                    downloads[acq.url] = Dl.Running(if (total > 0) (done * 100 / total).toInt() else -1)
                }
                downloads[acq.url] = Dl.Done(
                    if (result == AddResult.AlreadyThere) "Already in your library" else "Added to your library"
                )
                onAdded() // generate the cover and read the real title
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloads[acq.url] = Dl.Failed(
                    (e as? OnlineException)?.message ?: e.message ?: "The download failed."
                )
            }
        }
    }

    fun back() {
        if (stack.isNotEmpty()) {
            stack.removeAt(stack.lastIndex)
            error = null
        } else {
            onDismiss()
        }
    }

    val feed = stack.lastOrNull()

    Dialog(
        onDismissRequest = { back() }, // the phone's Back button goes up one catalog level
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { back() }) { Text(if (stack.isEmpty()) "Close" else "Back") }
                    Text(
                        feed?.title ?: "Add books online",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (feed == null) {
                    // ----- Start page -----
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "Paste an OPDS catalog address, or a direct link to a book file " +
                                    "(.epub, .pdf, .cbz or .txt).",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("Web address") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(onGo = { openAddress(input) })
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                // Reads the clipboard only when the user taps Paste
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                if (!text.isNullOrBlank()) input = text.trim()
                            }) { Text("Paste") }
                            Button(
                                enabled = !loading && input.isNotBlank(),
                                onClick = { openAddress(input) }
                            ) { Text("Open") }
                        }
                        Text(
                            "Try an example",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        TextButton(onClick = { input = GUTENBERG; openAddress(GUTENBERG) }) {
                            Text("Project Gutenberg (free classics)")
                        }
                        TextButton(onClick = { input = STANDARD_EBOOKS; openAddress(STANDARD_EBOOKS) }) {
                            Text("Standard Ebooks")
                        }
                        Text(
                            "Catalogs on your own computer or home server (Calibre, Calibre-Web, Komga) " +
                                    "work too. Use the address your server shows, for example " +
                                    "http://192.168.1.5:8080/opds. For a password-protected catalog, write it " +
                                    "as https://user:password@host/opds. Downloaded books are stored inside VIKAS.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                } else {
                    // ----- Catalog page -----
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (feed.searchTemplate != null || feed.searchDescUrl != null) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = searchText,
                                        onValueChange = { searchText = it },
                                        label = { Text("Search this catalog") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { search(feed) })
                                    )
                                    TextButton(onClick = { search(feed) }) { Text("Search") }
                                }
                            }
                        }
                        if (feed.entries.isEmpty()) {
                            item { Text("Nothing here.", Modifier.padding(top = 12.dp)) }
                        }
                        items(feed.entries) { entry ->
                            EntryCard(
                                entry = entry,
                                downloads = downloads,
                                onOpen = { navigate(it) },
                                onDownload = { download(entry, it) }
                            )
                        }
                        if (feed.nextUrl != null) {
                            item {
                                TextButton(enabled = !loading, onClick = { loadMore() }) {
                                    Text("Load more")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: OpdsEntry,
    downloads: Map<String, Dl>,
    onOpen: (String) -> Unit,
    onDownload: (Acquisition) -> Unit
) {
    val nav = entry.navUrl
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(if (nav != null) Modifier.clickable { onOpen(nav) } else Modifier)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            entry.author?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            entry.summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (nav != null) {
                Text(
                    "Open  \u203A",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            entry.acquisitions.forEach { acq ->
                when (val st = downloads[acq.url]) {
                    is Dl.Running -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            if (st.percent >= 0) "  Downloading ${st.percent}%" else "  Downloading...",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    is Dl.Done -> Text(
                        st.message,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    is Dl.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            st.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onDownload(acq) }) { Text("Retry") }
                    }
                    null -> TextButton(onClick = { onDownload(acq) }) {
                        Text("Download ${acq.format.uppercase()}")
                    }
                }
            }
            if (nav == null && entry.acquisitions.isEmpty()) {
                Text(
                    "Not available in a supported format (EPUB, PDF, CBZ or TXT).",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}