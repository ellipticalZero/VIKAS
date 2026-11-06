package com.example.vikas.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.vikas.data.db.AppDb
import com.example.vikas.data.db.BookEntity
import com.example.vikas.data.db.CollectionBookEntity
import com.example.vikas.data.db.CollectionEntity
import com.example.vikas.data.scan.FileScanner
import com.example.vikas.data.scan.MetadataExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDb.get(app)
    private val dao = db.bookDao()
    private val collectionDao = db.collectionDao()
    private val scanner = FileScanner(app)
    private val extractor = MetadataExtractor(app)

    val books: StateFlow<List<BookEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hiddenCount: StateFlow<Int> = dao.observeHiddenCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val collections: StateFlow<List<CollectionEntity>> = collectionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val links: StateFlow<List<CollectionBookEntity>> = collectionDao.observeAllLinks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val query = MutableStateFlow("")
    val selectedCollection = MutableStateFlow<Long?>(null) // null = All

    // Books after applying the collection filter and the search text
    val visibleBooks: StateFlow<List<BookEntity>> =
        combine(books, query, selectedCollection, links) { list, q, selected, links ->
            val inCollection = if (selected == null) list else {
                val uris = links.filter { it.collectionId == selected }.map { it.bookUri }.toSet()
                list.filter { it.uri in uris }
            }
            val t = q.trim()
            if (t.isEmpty()) inCollection
            else inCollection.filter {
                it.title.contains(t, ignoreCase = true) ||
                        it.author?.contains(t, ignoreCase = true) == true
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _lastScanCount = MutableStateFlow<Int?>(null)
    val lastScanCount: StateFlow<Int?> = _lastScanCount

    init {
        // Process any books that don't have a cover/metadata yet
        viewModelScope.launch { extractor.processPending() }
    }

    fun scan(treeUri: Uri) {
        viewModelScope.launch {
            _scanning.value = true
            val found = scanner.scan(treeUri)
            _lastScanCount.value = found.size
            dao.insertAll(found) // IGNORE: hidden books stay hidden
            _scanning.value = false
            extractor.processPending() // covers appear one by one as they finish
        }
    }

    fun createCollection(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val id = collectionDao.insert(CollectionEntity(name = clean))
            selectedCollection.value = id
        }
    }

    fun deleteCollection(id: Long) {
        viewModelScope.launch {
            selectedCollection.value = null
            // Two statements that must succeed or fail together: one transaction
            db.withTransaction {
                collectionDao.clearBooks(id)
                collectionDao.delete(id)
            }
        }
    }

    fun setMembership(collectionId: Long, bookUri: String, member: Boolean) {
        viewModelScope.launch {
            if (member) collectionDao.addBook(CollectionBookEntity(collectionId, bookUri))
            else collectionDao.removeBook(collectionId, bookUri)
        }
    }

    // Add many books to one collection in a single database transaction
    fun addBooksToCollection(collectionId: Long, uris: Collection<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            collectionDao.addBooks(uris.map { CollectionBookEntity(collectionId, it) })
        }
    }

    // Hide a book. Its progress, bookmarks and notes are kept in case it is restored.
    fun removeBook(uri: String) {
        viewModelScope.launch { dao.hide(uri) }
    }

    fun restoreRemoved() {
        viewModelScope.launch {
            dao.restoreAllHidden()
            extractor.processPending()
        }
    }

    // A book was downloaded: read its title and make its cover
    fun onBookAdded() {
        viewModelScope.launch { extractor.processPending() }
    }

}