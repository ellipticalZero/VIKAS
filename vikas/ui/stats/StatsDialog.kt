package com.example.vikas.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.vikas.data.db.AppDb
import com.example.vikas.data.db.ReadingSessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

private data class Stats(
    val totalSec: Long,
    val todaySec: Long,
    val streak: Int,
    val pages: Int,
    val sessions: Int,
    val week: List<Pair<LocalDate, Long>>
)

private fun computeStats(list: List<ReadingSessionEntity>): Stats {
    val zone = ZoneId.systemDefault()
    val byDay = list
        .groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        .mapValues { (_, v) -> v.sumOf { it.durationSec.toLong() } }
    val today = LocalDate.now(zone)

    // Streak: consecutive days with reading, ending today (or yesterday if not read yet today)
    var streak = 0
    var day = if ((byDay[today] ?: 0L) > 0) today else today.minusDays(1)
    while ((byDay[day] ?: 0L) > 0) {
        streak++
        day = day.minusDays(1)
    }

    val week = (6 downTo 0).map { back ->
        val d = today.minusDays(back.toLong())
        d to (byDay[d] ?: 0L)
    }
    return Stats(
        totalSec = list.sumOf { it.durationSec.toLong() },
        todaySec = byDay[today] ?: 0L,
        streak = streak,
        pages = list.sumOf { it.pagesViewed },
        sessions = list.size,
        week = week
    )
}

private fun fmt(sec: Long): String = when {
    sec <= 0L -> "0m"
    sec < 60 -> "<1m"
    sec < 3600 -> "${sec / 60}m"
    else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
}

@Composable
fun StatsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDb.get(context) }
    val sessions by remember { db.statsDao().observeAll() }.collectAsState(initial = emptyList())
    val books by remember { db.bookDao().observeAll() }.collectAsState(initial = emptyList())
    val stats = remember(sessions) { computeStats(sessions) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Reading statistics") },
        text = {
            if (sessions.isEmpty()) {
                Text("No reading sessions yet. Read for a few seconds, then go back, and they will appear here.")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth()) {
                        StatCell(fmt(stats.totalSec), "Total time")
                        StatCell(fmt(stats.todaySec), "Today")
                        StatCell("${stats.streak} day${if (stats.streak == 1) "" else "s"}", "Streak")
                    }
                    Text(
                        "${stats.sessions} sessions, ${stats.pages} pages viewed",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    Text(
                        "Last 7 days",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    val maxSec = (stats.week.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
                    Row(
                        Modifier.fillMaxWidth().height(110.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        stats.week.forEach { (date, sec) ->
                            Column(
                                Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                val h = if (sec == 0L) 2f else (sec.toFloat() / maxSec * 80f).coerceAtLeast(4f)
                                Box(
                                    Modifier
                                        .width(18.dp)
                                        .height(h.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Text(
                                    date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.getDefault()),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    // Most-read books
                    val titles = books.associate { it.uri to it.title }
                    val top = sessions
                        .groupBy { it.bookUri }
                        .mapValues { (_, v) -> v.sumOf { it.durationSec.toLong() } }
                        .entries.sortedByDescending { it.value }
                        .filter { it.key in titles }
                        .take(3)
                    if (top.isNotEmpty()) {
                        Text(
                            "Most read",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                        top.forEach { (uri, sec) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    titles[uri] ?: "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                                Text(fmt(sec), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun RowScope.StatCell(value: String, label: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}