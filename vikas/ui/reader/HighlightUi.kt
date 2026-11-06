package com.example.vikas.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.vikas.data.db.HighlightEntity

object HighlightColors {
    val names = listOf("Yellow", "Green", "Blue", "Pink")
    private val base = listOf(0xFFFFEB3B, 0xFF66BB6A, 0xFF42A5F5, 0xFFF06292)
    private val lightCss = listOf("#FFF59D", "#A5D6A7", "#90CAF9", "#F8BBD0")
    private val darkCss = listOf(
        "rgba(255,235,59,0.40)", "rgba(102,187,106,0.45)",
        "rgba(66,165,245,0.45)", "rgba(240,98,146,0.45)"
    )

    fun swatch(id: Int): Color = Color(base[id.coerceIn(0, 3)])
    fun overlay(id: Int): Color = swatch(id).copy(alpha = 0.40f)   // drawn over TXT pages
    fun css(id: Int, dark: Boolean): String =                       // used inside EPUB pages
        if (dark) darkCss[id.coerceIn(0, 3)] else lightCss[id.coerceIn(0, 3)]
}

// What the page content needs from the reader screen to support highlights
class HighlightApi(
    val all: List<HighlightEntity>,
    val create: (chapter: Int, start: Int, end: Int, text: String) -> Unit,
    val open: (id: Long) -> Unit
)

@Composable
fun HighlightDialog(
    h: HighlightEntity,
    onSave: (color: Int, note: String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var color by remember(h.id) { mutableIntStateOf(h.color) }
    var note by remember(h.id) { mutableStateOf(h.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Highlight") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "\u201c${h.text}\u201d",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HighlightColors.names.indices.forEach { i ->
                        val chosen = color == i
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(HighlightColors.swatch(i))
                                .border(
                                    if (chosen) 3.dp else 1.dp,
                                    if (chosen) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                                .clickable { color = i }
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(1000) },
                    label = { Text("Note (optional)") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onDelete) {
                    Text("Delete highlight", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(color, note.trim().ifEmpty { null }) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun HighlightListDialog(
    highlights: List<HighlightEntity>,
    pageOf: (HighlightEntity) -> Int,
    emptyHint: String,
    onJump: (Int) -> Unit,
    onEdit: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Highlights") },
        text = {
            if (highlights.isEmpty()) {
                Text("No highlights yet. $emptyHint")
            } else {
                LazyColumn {
                    items(highlights, key = { it.id }) { h ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onJump(pageOf(h)) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(Modifier.width(6.dp).height(52.dp).background(HighlightColors.swatch(h.color)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    h.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!h.note.isNullOrBlank()) {
                                    Text(
                                        h.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontStyle = FontStyle.Italic,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text("Page ${pageOf(h) + 1}", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onEdit(h.id) }) { Text("Edit") }
                        }
                    }
                }
            }
        }
    )
}