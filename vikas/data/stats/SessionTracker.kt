package com.example.vikas.data.stats

import com.example.vikas.data.db.ReadingSessionEntity
import com.example.vikas.data.db.StatsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Measures one reading session. All calls happen on the main thread.
class SessionTracker(
    private val dao: StatsDao,
    private val bookUri: String
) {
    private var startMs = 0L
    private val pages = mutableSetOf<Int>()

    fun start() {
        if (startMs == 0L) startMs = System.currentTimeMillis()
    }

    fun onPage(page: Int) {
        pages += page
    }

    // Ends the session and saves it. Safe to call twice: the second call does nothing.
    fun flush() {
        val start = startMs
        if (start == 0L) return
        startMs = 0L
        val seconds = ((System.currentTimeMillis() - start) / 1000).toInt()
        val viewed = pages.size
        pages.clear()
        if (seconds < 5) return // ignore accidental opens

        // Own scope, so the save still completes after the reader screen is gone
        CoroutineScope(Dispatchers.IO).launch {
            dao.insert(
                ReadingSessionEntity(
                    bookUri = bookUri,
                    startedAt = start,
                    durationSec = seconds,
                    pagesViewed = viewed
                )
            )
        }
    }
}