package com.example.vikas.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.vikas.data.readers.SearchHit
import kotlinx.coroutines.launch

@Composable
fun SearchDialog(
    onSearch: suspend (String) -> List<SearchHit>,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run() {
        val q = query.trim()
        if (q.isEmpty() || busy) return
        scope.launch {
            busy = true
            hits = try { onSearch(q) } catch (e: Exception) { emptyList() }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Search in book") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { run() })
                    )
                    TextButton(onClick = { run() }) { Text("Go") }
                }
                val result = hits
                when {
                    busy -> CircularProgressIndicator(Modifier.padding(16.dp))
                    result == null -> {}
                    result.isEmpty() -> Text("No matches.", Modifier.padding(top = 12.dp))
                    else -> {
                        Text(
                            "${result.size} matches",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(result) { hit ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onJump(hit.page) }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        "Page ${hit.page + 1}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Text(hit.snippet, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}