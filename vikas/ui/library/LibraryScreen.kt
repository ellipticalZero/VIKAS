package com.example.vikas.ui.library

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vikas.data.db.BookEntity
import com.example.vikas.data.db.CollectionEntity
import com.example.vikas.ui.settings.SettingsDialog
import com.example.vikas.ui.stats.StatsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.vikas.ui.online.OnlineDialog

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onOpen: (BookEntity) -> Unit,
    vm: LibraryViewModel = viewModel()
) {
    val context = LocalContext.current
    val scanning by vm.scanning.collectAsState()
    val query by vm.query.collectAsState()
    val visible by vm.visibleBooks.collectAsState()
    val allBooks by vm.books.collectAsState()
    val hiddenCount by vm.hiddenCount.collectAsState()
    val collections by vm.collections.collectAsState()
    val links by vm.links.collectAsState()
    val selected by vm.selectedCollection.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showAddBooks by remember { mutableStateOf(false) }
    var actionBook by remember { mutableStateOf<BookEntity?>(null) }      // long-press menu
    var bookToFile by remember { mutableStateOf<BookEntity?>(null) }      // collections checklist
    var bookToRemove by remember { mutableStateOf<BookEntity?>(null) }    // remove confirmation
    var collectionToDelete by remember { mutableStateOf<CollectionEntity?>(null) }
    var showOnline by remember { mutableStateOf(false) }
    val lastScanCount by vm.lastScanCount.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            vm.scan(it)
        }
    }

    val current = collections.firstOrNull { it.id == selected }

    if (allBooks.isEmpty()) {
        EmptyLibrary(
            modifier = modifier,
            scanning = scanning,
            lastScanCount = lastScanCount,
            hiddenCount = hiddenCount,
            onChooseFolder = { folderPicker.launch(null) },
            onAddOnline = { showOnline = true },
            onSettings = { showSettings = true },
            onRestoreRemoved = { vm.restoreRemoved() }
        )
    } else Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { folderPicker.launch(null) }) {
                Text("Choose books folder")
            }
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
            TextButton(onClick = { showStats = true }) { Text("Stats") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (scanning) "Scanning..." else "${visible.size} books",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            TextButton(onClick = { showOnline = true }) { Text("Add online") }
            if (hiddenCount > 0) {
                TextButton(onClick = { vm.restoreRemoved() }) {
                    Text("Restore $hiddenCount removed")
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            label = { Text("Search library") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )

        // Collection chips: All, each collection, and a "+ New" button
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selected == null,
                    onClick = { vm.selectedCollection.value = null },
                    label = { Text("All") }
                )
            }
            items(collections, key = { it.id }) { c ->
                FilterChip(
                    selected = selected == c.id,
                    onClick = { vm.selectedCollection.value = c.id },
                    label = { Text(c.name) }
                )
            }
            item {
                AssistChip(onClick = { showNew = true }, label = { Text("+ New") })
            }
        }

        if (current != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showAddBooks = true }) { Text("Add books") }
                TextButton(onClick = { collectionToDelete = current }) { Text("Delete collection") }
            }
            if (visible.isEmpty() && query.isBlank()) {
                Text(
                    "This collection is empty. Tap Add books to fill it.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        } else if (collections.isEmpty()) {
            Text(
                "Tip: tap + New to create a collection. Long-press a book for more options.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            if (visible.isEmpty() && query.isNotBlank()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No books match \"${query.trim()}\"")
                        TextButton(onClick = { vm.query.value = "" }) { Text("Clear search") }
                    }
                }
            }

            items(visible, key = { it.uri }) { book ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onOpen(book) },
                            onLongClick = { actionBook = book }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CoverImage(book.coverPath, book.format)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                book.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!book.author.isNullOrBlank()) {
                                Text(
                                    book.author,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                book.format.uppercase(),
                                style = MaterialTheme.typography.labelMedium
                            )
                            if (book.totalPages > 0) {
                                Text(
                                    "Page ${book.lastPage + 1} of ${book.totalPages}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
    if (showStats) StatsDialog(onDismiss = { showStats = false })
    if (showOnline) {
        OnlineDialog(
            onDismiss = { showOnline = false },
            onAdded = { vm.onBookAdded() }
        )
    }

    // ----- New collection -----
    if (showNew) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("New collection") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { vm.createCollection(name); showNew = false }
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } }
        )
    }

    // ----- Delete collection confirmation -----
    collectionToDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { collectionToDelete = null },
            title = { Text("Delete collection?") },
            text = { Text("\"${c.name}\" will be deleted. The books in it will not be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCollection(c.id)
                    collectionToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { collectionToDelete = null }) { Text("Cancel") }
            }
        )
    }

    // ----- Add several books to the selected collection -----
    if (showAddBooks && current != null) {
        val memberUris = links.filter { it.collectionId == current.id }.map { it.bookUri }.toSet()
        AddBooksDialog(
            collectionName = current.name,
            candidates = allBooks.filter { it.uri !in memberUris },
            onAdd = { uris ->
                vm.addBooksToCollection(current.id, uris)
                showAddBooks = false
            },
            onDismiss = { showAddBooks = false }
        )
    }

    // ----- Long-press menu for a book -----
    actionBook?.let { book ->
        AlertDialog(
            onDismissRequest = { actionBook = null },
            title = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (current != null) {
                        TextButton(onClick = {
                            vm.setMembership(current.id, book.uri, false)
                            actionBook = null
                        }) {
                            Text("Remove from \"${current.name}\"")
                        }
                    }
                    TextButton(onClick = { bookToFile = book; actionBook = null }) {
                        Text("Add to collections")
                    }
                    TextButton(onClick = { bookToRemove = book; actionBook = null }) {
                        Text("Remove from library")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actionBook = null }) { Text("Cancel") } }
        )
    }

    // ----- Remove confirmation -----
    bookToRemove?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToRemove = null },
            title = { Text("Remove from library?") },
            text = {
                Text(
                    "\"${book.title}\" will be hidden from your library. The file on your phone is " +
                            "not deleted, and your progress, bookmarks and notes are kept if you restore it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeBook(book.uri)
                    bookToRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { bookToRemove = null }) { Text("Cancel") } }
        )
    }

    // ----- Collections checklist for one book -----
    bookToFile?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToFile = null },
            title = { Text("Add to collections") },
            text = {
                if (collections.isEmpty()) {
                    Text("No collections yet. Tap + New to create one first.")
                } else {
                    Column {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        collections.forEach { c ->
                            val member = links.any {
                                it.collectionId == c.id && it.bookUri == book.uri
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = member,
                                    onCheckedChange = { vm.setMembership(c.id, book.uri, it) }
                                )
                                Text(c.name)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { bookToFile = null }) { Text("Done") } }
        )
    }
}

@Composable
private fun AddBooksDialog(
    collectionName: String,
    candidates: List<BookEntity>,
    onAdd: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val shown = remember(candidates, filter) {
        val t = filter.trim()
        if (t.isEmpty()) candidates
        else candidates.filter {
            it.title.contains(t, ignoreCase = true) ||
                    it.author?.contains(t, ignoreCase = true) == true
        }
    }
    val shownUris = remember(shown) { shown.map { it.uri }.toSet() }
    val allShownSelected = shownUris.isNotEmpty() && selected.containsAll(shownUris)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add books to \"$collectionName\"", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            if (candidates.isEmpty()) {
                Text("All your books are already in this collection.")
            } else {
                Column {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text("Filter books") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = shownUris.isNotEmpty(),
                            onClick = {
                                selected = if (allShownSelected) selected - shownUris
                                else selected + shownUris
                            }
                        ) { Text(if (allShownSelected) "Clear shown" else "Select all shown") }
                        Text("${selected.size} selected", style = MaterialTheme.typography.labelMedium)
                    }
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(shown, key = { it.uri }) { b ->
                            val checked = b.uri in selected
                            val toggle = {
                                selected = if (checked) selected - b.uri else selected + b.uri
                            }
                            Row(
                                Modifier.fillMaxWidth().clickable { toggle() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { toggle() })
                                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                    Text(
                                        b.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        listOfNotNull(b.author?.takeIf { it.isNotBlank() }, b.format.uppercase())
                                            .joinToString("  |  "),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = { onAdd(selected) }) {
                Text("Add ${selected.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CoverImage(path: String?, format: String) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = path?.let { p ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p)?.asImageBitmap() }
        }
    }
    Box(
        modifier = Modifier
            .size(width = 60.dp, height = 90.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(format.uppercase(), style = MaterialTheme.typography.labelMedium)
        }
    }
}