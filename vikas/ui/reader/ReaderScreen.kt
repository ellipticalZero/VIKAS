package com.example.vikas.ui.reader

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.vikas.data.db.AppDb
import com.example.vikas.data.db.BookEntity
import com.example.vikas.data.db.BookmarkEntity
import com.example.vikas.data.db.HighlightEntity
import com.example.vikas.data.readers.BookReader
import com.example.vikas.data.readers.BookReaders
import com.example.vikas.data.readers.EpubBook
import com.example.vikas.data.readers.EpubSearcher
import com.example.vikas.data.readers.EpubSpeaker
import com.example.vikas.data.readers.PageCache
import com.example.vikas.data.readers.PagedTextReader
import com.example.vikas.data.readers.Searchable
import com.example.vikas.data.readers.Speakable
import com.example.vikas.data.stats.SessionTracker
import com.example.vikas.data.tts.TtsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SELECTION_COLOR = Color(0xFF42A5F5).copy(alpha = 0.35f)

@Composable
fun ReaderScreen(
    book: BookEntity,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    if (book.format == "epub") EpubReader(book, modifier) else BitmapReader(book, modifier)
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

// ---------- PDF / CBZ / TXT: pages are bitmaps ----------

@Composable
private fun BitmapReader(book: BookEntity, modifier: Modifier) {
    val context = LocalContext.current
    var reader by remember(book.uri) { mutableStateOf<BookReader?>(null) }
    var error by remember(book.uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(book.uri) {
        try {
            reader = BookReaders.open(context, book)
        } catch (e: Exception) {
            error = e.message ?: "Could not open this file"
        }
    }
    DisposableEffect(book.uri) { onDispose { reader?.close() } }

    val r = reader
    val err = error
    when {
        err != null -> Centered(modifier) { Text(err) }
        r == null -> Centered(modifier) { CircularProgressIndicator() }
        else -> {
            val cache = remember(book.uri) { PageCache() }
            val filter = pageFilter(book.format)
            val txt = r as? PagedTextReader
            ReaderScaffold(
                book = book,
                pageCount = r.pageCount,
                modifier = modifier,
                searchable = r as? Searchable,
                speakable = r as? Speakable,
                highlightPage = txt?.let { t -> { h: HighlightEntity -> t.pageOfOffset(h.startOffset) } },
                highlightHint = "Long-press a word, drag to extend the selection, then tap Highlight."
            ) { page, widthPx, onTap, hl ->
                BitmapPage(r, cache, page, widthPx, onTap, filter, hl)
            }
        }
    }
}

@Composable
private fun BitmapPage(
    reader: BookReader,
    cache: PageCache,
    page: Int,
    widthPx: Int,
    onTap: () -> Unit,
    colorFilter: ColorFilter?,
    hl: HighlightApi
) {
    var bitmap by remember(page) { mutableStateOf(cache.get(page)) }

    LaunchedEffect(page) {
        if (bitmap == null) {
            val bmp = reader.renderPage(page, widthPx)
            cache.put(page, bmp)
            bitmap = bmp
        }
    }

    val bmp = bitmap
    if (bmp == null) {
        Centered(Modifier) { CircularProgressIndicator() }
        return
    }

    val txt = reader as? PagedTextReader

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight

        // TXT only: the current selection, and the highlights that touch this page
        var selStart by remember(page) { mutableStateOf<Int?>(null) }
        var selEnd by remember(page) { mutableIntStateOf(0) }
        var anchorStart by remember(page) { mutableIntStateOf(0) }
        var anchorEnd by remember(page) { mutableIntStateOf(0) }
        var dragging by remember(page) { mutableStateOf(false) }

        val pageHl = remember(hl.all, page, txt) {
            if (txt == null) emptyList() else {
                val from = txt.pageStartOf(page)
                val to = txt.pageEndOf(page)
                hl.all.filter { it.chapter == 0 && it.startOffset < to && it.endOffset > from }
            }
        }
        val hlPaths = remember(pageHl, txt) {
            txt?.let { t ->
                pageHl.map { h ->
                    t.selectionPath(page, h.startOffset, h.endOffset).asComposePath() to
                            HighlightColors.overlay(h.color)
                }
            } ?: emptyList()
        }
        val selPath = remember(selStart, selEnd) {
            val s = selStart
            if (txt != null && s != null && selEnd > s) {
                txt.selectionPath(page, s, selEnd).asComposePath()
            } else null
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { onTap() } }
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight),
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (txt == null) Modifier else Modifier
                            // Tap: open the highlight under the finger, or toggle the controls
                            .pointerInput(page, pageHl) {
                                detectTapGestures(onTap = { pos ->
                                    val k = size.width / PagedTextReader.PAGE_W.toFloat()
                                    val off = txt.offsetAt(page, pos.x / k, pos.y / k)
                                    val hit = pageHl.firstOrNull { off >= it.startOffset && off < it.endOffset }
                                    when {
                                        selStart != null -> selStart = null
                                        hit != null -> hl.open(hit.id)
                                        else -> onTap()
                                    }
                                })
                            }
                            // Long-press a word, then drag to extend the selection
                            .pointerInput(page) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { pos ->
                                        val k = size.width / PagedTextReader.PAGE_W.toFloat()
                                        val w = txt.wordRange(txt.offsetAt(page, pos.x / k, pos.y / k))
                                        anchorStart = w.first
                                        anchorEnd = w.last + 1
                                        selStart = anchorStart
                                        selEnd = anchorEnd
                                        dragging = true
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        val k = size.width / PagedTextReader.PAGE_W.toFloat()
                                        val off = txt.offsetAt(
                                            page, change.position.x / k, change.position.y / k
                                        )
                                        selStart = minOf(anchorStart, off)
                                        selEnd = maxOf(anchorEnd, off)
                                    },
                                    onDragEnd = { dragging = false },
                                    onDragCancel = { dragging = false; selStart = null }
                                )
                            }
                    )
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${page + 1}",
                    contentScale = ContentScale.FillWidth,
                    colorFilter = colorFilter,
                    modifier = Modifier.fillMaxWidth()
                )
                if (txt != null) {
                    // Highlights and the selection are drawn over the page picture
                    Canvas(Modifier.matchParentSize()) {
                        val k = size.width / PagedTextReader.PAGE_W
                        clipRect(
                            0f, PagedTextReader.MARGIN * k,
                            size.width, (PagedTextReader.PAGE_H - PagedTextReader.MARGIN) * k
                        ) {
                            scale(k, pivot = Offset.Zero) {
                                hlPaths.forEach { (path, color) -> drawPath(path, color) }
                                selPath?.let { drawPath(it, SELECTION_COLOR) }
                            }
                        }
                    }
                }
            }
        }

        // Action bar shown after a selection is made on a TXT page
        val s = selStart
        if (txt != null && s != null && !dragging) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 6.dp,
                shadowElevation = 4.dp
            ) {
                Row {
                    TextButton(onClick = {
                        if (selEnd > s) hl.create(0, s, selEnd, txt.snippet(s, selEnd))
                        selStart = null
                    }) { Text("Highlight") }
                    TextButton(onClick = { selStart = null }) { Text("Cancel") }
                }
            }
        }
    }
}

// ---------- EPUB: pages are chapters shown in a WebView ----------

@Composable
private fun EpubReader(book: BookEntity, modifier: Modifier) {
    val context = LocalContext.current
    var epub by remember(book.uri) { mutableStateOf<EpubBook?>(null) }
    var error by remember(book.uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(book.uri) {
        try {
            epub = withContext(Dispatchers.IO) {
                EpubBook.open(context, android.net.Uri.parse(book.uri))
            }
        } catch (e: Exception) {
            error = e.message ?: "Could not open this EPUB"
        }
    }
    DisposableEffect(book.uri) { onDispose { epub?.close() } }

    val e = epub
    val err = error
    when {
        err != null -> Centered(modifier) { Text(err) }
        e == null -> Centered(modifier) { CircularProgressIndicator() }
        else -> {
            val searcher = remember(e) { EpubSearcher(e) }
            val speaker = remember(e) { EpubSpeaker(e) }
            ReaderScaffold(
                book = book,
                pageCount = e.chapterCount,
                modifier = modifier,
                searchable = searcher,
                speakable = speaker,
                highlightPage = { h -> h.chapter },
                highlightHint = "Long-press a word, drag the handles to select, then tap Highlight in the menu."
            ) { page, _, onTap, hl ->
                EpubChapterView(
                    book = e,
                    chapter = page,
                    highlights = hl.all.filter { it.chapter == page },
                    onTap = onTap,
                    onHighlightSelected = { s, en, t -> hl.create(page, s, en, t) },
                    onHighlightTapped = { hl.open(it) }
                )
            }
        }
    }
}

// ---------- Shared: pager, progress, bookmarks, notes, highlights, search, statistics, read aloud ----------

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f)

@Composable
private fun BarButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(text)
    }
}

@Composable
private fun ReaderScaffold(
    book: BookEntity,
    pageCount: Int,
    modifier: Modifier = Modifier,
    searchable: Searchable? = null,
    speakable: Speakable? = null,
    highlightPage: ((HighlightEntity) -> Int)? = null,   // null = this format has no highlights
    highlightHint: String = "",
    pageContent: @Composable (page: Int, widthPx: Int, onTap: () -> Unit, hl: HighlightApi) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDb.get(context) }
    val dao = remember { db.bookDao() }
    val bookmarkDao = remember { db.bookmarkDao() }
    val highlightDao = remember { db.highlightDao() }
    val tracker = remember(book.uri) { SessionTracker(db.statsDao(), book.uri) }

    // Read-aloud state (declared before the lifecycle observer that uses it)
    var reading by remember { mutableStateOf(false) }
    var rate by remember { mutableFloatStateOf(1.0f) }
    var ttsProblem by remember { mutableStateOf<String?>(null) }
    val tts = remember(book.uri) { if (speakable != null) TtsController(context) else null }
    DisposableEffect(tts) { onDispose { tts?.shutdown() } }

    // Count reading time only while the app is in the foreground; stop speech when it leaves
    DisposableEffect(tracker) {
        val lifecycle = (context as LifecycleOwner).lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> tracker.start()
                Lifecycle.Event.ON_STOP -> {
                    tracker.flush()
                    reading = false
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            tracker.flush() // leaving the reader ends the session
        }
    }

    // Keep the screen awake while listening
    DisposableEffect(reading) {
        view.keepScreenOn = reading
        onDispose { view.keepScreenOn = false }
    }

    val pagerState = rememberPagerState(
        initialPage = book.lastPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        pageCount = { pageCount }
    )

    // Save progress every time the page changes
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            tracker.onPage(page)
            dao.updateProgress(book.uri, page, pageCount, System.currentTimeMillis())
        }
    }

    // Read aloud: speak the current page; when finished, turn to the next page and repeat.
    // Swiping to another page, or changing the speed, restarts speech from that page.
    val blankPages = remember(book.uri) { intArrayOf(0) }
    val currentPage = pagerState.currentPage
    LaunchedEffect(reading, currentPage, rate) {
        val t = tts ?: return@LaunchedEffect
        val source = speakable ?: return@LaunchedEffect
        if (!reading) {
            t.stop()
            blankPages[0] = 0
            return@LaunchedEffect
        }
        t.rate = rate
        t.onProblem = { msg ->
            ttsProblem = msg
            reading = false
        }
        t.onFinished = {
            if (currentPage + 1 < pageCount) {
                scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
            } else {
                reading = false
            }
        }
        val pageText = source.pageText(currentPage)
        if (book.format == "pdf") {
            if (pageText.isBlank()) {
                blankPages[0]++
                if (blankPages[0] >= 3) {
                    blankPages[0] = 0
                    ttsProblem = "No readable text was found on these pages. " +
                            "Scanned PDFs are pictures of text and cannot be read aloud."
                    reading = false
                    return@LaunchedEffect
                }
            } else {
                blankPages[0] = 0
            }
        }
        t.speak(pageText)
    }

    val bookmarks by remember(book.uri) { bookmarkDao.observeFor(book.uri) }
        .collectAsState(initial = emptyList())
    val here = bookmarks.firstOrNull { it.page == pagerState.currentPage }
    val isBookmarked = here != null
    val hasNote = !here?.note.isNullOrBlank()

    // ----- Highlights -----
    val highlights by remember(book.uri) { highlightDao.observeFor(book.uri) }
        .collectAsState(initial = emptyList())
    var defaultColor by remember { mutableIntStateOf(0) }
    var editingHighlight by remember { mutableStateOf<Long?>(null) }
    var showHighlights by remember { mutableStateOf(false) }

    fun createHighlight(chapter: Int, start: Int, end: Int, text: String) {
        if (end <= start) return
        if (highlights.any { it.chapter == chapter && it.startOffset < end && start < it.endOffset }) {
            Toast.makeText(
                context,
                "That text is already highlighted. Tap the highlight to change or remove it.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val snippet = text.replace(Regex("\\s+"), " ").trim().take(300)
        scope.launch {
            highlightDao.insert(
                HighlightEntity(
                    bookUri = book.uri,
                    chapter = chapter,
                    startOffset = start,
                    endOffset = end,
                    text = snippet,
                    color = defaultColor,
                    note = null,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    val highlightApi = HighlightApi(
        all = highlights,
        create = { chapter, start, end, text -> createHighlight(chapter, start, end, text) },
        open = { id -> editingHighlight = id }
    )

    var showList by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showDictionary by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var noteForPage by remember { mutableStateOf<Int?>(null) }
    var bookmarkToDelete by remember { mutableStateOf<BookmarkEntity?>(null) }

    // Removing a bookmark that has a note asks first, because the note is deleted with it
    fun removeBookmark(b: BookmarkEntity) {
        if (b.note.isNullOrBlank()) scope.launch { bookmarkDao.deleteAt(book.uri, b.page) }
        else bookmarkToDelete = b
    }

    // Tap the page to hide or show the bottom bar. Change to false to start hidden.
    var showBar by remember { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxSize().background(readerBackground())) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1 // prefetch the pages on either side
            ) { page ->
                pageContent(page, widthPx, { showBar = !showBar }, highlightApi)
            }
        }

        if (showBar) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BarButton(if (!isBookmarked) "☆" else if (hasNote) "★✎" else "★") {
                    val page = pagerState.currentPage
                    if (here != null) removeBookmark(here)
                    else scope.launch {
                        bookmarkDao.insert(BookmarkEntity(bookUri = book.uri, page = page))
                    }
                }
                Text("${pagerState.currentPage + 1} / $pageCount")
                if (speakable != null) {
                    BarButton(if (reading) "Stop" else "Listen") {
                        reading = !reading
                        if (reading) ttsProblem = null
                    }
                }
                if (reading) {
                    BarButton("${rate}x") {
                        rate = SPEEDS[(SPEEDS.indexOf(rate) + 1) % SPEEDS.size]
                    }
                }
                Box {
                    BarButton("More") { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (hasNote) "Edit note for this page" else "Add note to this page") },
                            onClick = { menuOpen = false; noteForPage = pagerState.currentPage }
                        )
                        if (highlightPage != null) {
                            DropdownMenuItem(
                                text = { Text("Highlights (${highlights.size})") },
                                onClick = { menuOpen = false; showHighlights = true }
                            )
                        }
                        if (searchable != null) {
                            DropdownMenuItem(
                                text = { Text("Search in book") },
                                onClick = { menuOpen = false; showSearch = true }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Dictionary") },
                            onClick = { menuOpen = false; showDictionary = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Bookmarks and notes (${bookmarks.size})") },
                            onClick = { menuOpen = false; showList = true }
                        )
                    }
                }
            }
        }
    }

    // ----- Read-aloud problem dialog -----
    ttsProblem?.let { msg ->
        AlertDialog(
            onDismissRequest = { ttsProblem = null },
            confirmButton = { TextButton(onClick = { ttsProblem = null }) { Text("OK") } },
            title = { Text("Read aloud") },
            text = { Text(msg) }
        )
    }

    // ----- Dictionary dialog -----
    if (showDictionary) DictionaryDialog(onDismiss = { showDictionary = false })

    // ----- Edit one highlight -----
    editingHighlight?.let { id ->
        highlights.firstOrNull { it.id == id }?.let { h ->
            HighlightDialog(
                h = h,
                onSave = { color, note ->
                    scope.launch { highlightDao.update(h.id, color, note) }
                    defaultColor = color // new highlights use the colour you picked last
                    editingHighlight = null
                },
                onDelete = {
                    scope.launch { highlightDao.delete(h.id) }
                    editingHighlight = null
                },
                onDismiss = { editingHighlight = null }
            )
        }
    }

    // ----- All highlights in this book -----
    if (showHighlights && highlightPage != null) {
        HighlightListDialog(
            highlights = highlights,
            pageOf = highlightPage,
            emptyHint = highlightHint,
            onJump = { page ->
                showHighlights = false
                scope.launch { pagerState.scrollToPage(page) }
            },
            onEdit = { id ->
                showHighlights = false
                editingHighlight = id
            },
            onDismiss = { showHighlights = false }
        )
    }

    // ----- Note editor: updates the page's bookmark, or creates one -----
    noteForPage?.let { page ->
        val existing = bookmarks.firstOrNull { it.page == page }
        NoteDialog(
            page = page,
            initial = existing?.note.orEmpty(),
            onSave = { text ->
                val clean = text.trim()
                scope.launch {
                    if (existing != null) {
                        bookmarkDao.updateNote(existing.id, clean.ifEmpty { null })
                    } else if (clean.isNotEmpty()) {
                        bookmarkDao.insert(BookmarkEntity(bookUri = book.uri, page = page, note = clean))
                    }
                }
                noteForPage = null
            },
            onDismiss = { noteForPage = null }
        )
    }

    // ----- Confirm removing a bookmark that has a note -----
    bookmarkToDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { bookmarkToDelete = null },
            title = { Text("Remove bookmark?") },
            text = { Text("Page ${b.page + 1} has a note. Removing the bookmark deletes the note too.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { bookmarkDao.deleteAt(book.uri, b.page) }
                    bookmarkToDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { bookmarkToDelete = null }) { Text("Cancel") } }
        )
    }

    // ----- Bookmarks and notes dialog -----
    if (showList) {
        AlertDialog(
            onDismissRequest = { showList = false },
            confirmButton = { TextButton(onClick = { showList = false }) { Text("Close") } },
            title = { Text("Bookmarks and notes") },
            text = {
                if (bookmarks.isEmpty()) {
                    Text("No bookmarks yet. Tap the star to bookmark a page, or add a note from the More menu.")
                } else {
                    LazyColumn {
                        items(bookmarks, key = { it.id }) { b ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            showList = false
                                            scope.launch { pagerState.scrollToPage(b.page) }
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text("Page ${b.page + 1}")
                                    if (!b.note.isNullOrBlank()) {
                                        Text(
                                            b.note,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = { showList = false; noteForPage = b.page },
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) { Text(if (b.note.isNullOrBlank()) "Note" else "Edit") }
                                TextButton(
                                    onClick = { removeBookmark(b) },
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        )
    }

    // ----- Search dialog (separate from the bookmarks dialog) -----
    if (showSearch && searchable != null) {
        SearchDialog(
            onSearch = { searchable.search(it) },
            onJump = { page ->
                showSearch = false
                scope.launch { pagerState.scrollToPage(page) }
            },
            onDismiss = { showSearch = false }
        )
    }
}