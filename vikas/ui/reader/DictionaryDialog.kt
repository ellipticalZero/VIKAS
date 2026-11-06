package com.example.vikas.ui.reader

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.vikas.data.db.AppDb
import com.example.vikas.data.dictionary.DictionaryRepository
import com.example.vikas.data.dictionary.LookupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DictionaryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { DictionaryRepository(context) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val recent by remember { AppDb.get(context).dictionaryDao().recent(8) }
        .collectAsState(initial = emptyList())

    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<LookupResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun lookup(word: String) {
        if (word.isBlank() || busy) return
        keyboard?.hide()      // free up the screen so the result is visible
        focus.clearFocus()
        scope.launch {
            busy = true
            failure = null
            try {
                result = repo.lookup(word)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result = null
                failure = e.message ?: e.toString()
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Dictionary") },
        text = {
            Column {
                // Fixed header: input and buttons
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Word") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { lookup(input) })
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        // Reads the clipboard only when the user taps Paste
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrBlank()) {
                            input = text.trim().take(40)
                            lookup(input)
                        }
                    }) { Text("Paste") }
                    Button(enabled = !busy && input.isNotBlank(), onClick = { lookup(input) }) {
                        Text("Look up")
                    }
                    if (busy) CircularProgressIndicator(Modifier.padding(start = 4.dp))
                }

                // Scrollable area: the result first, then recent words
                Column(
                    Modifier
                        .padding(top = 12.dp)
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    failure?.let {
                        Text("Something went wrong: $it", color = MaterialTheme.colorScheme.error)
                    }

                    when (val r = result) {
                        null -> {}
                        is LookupResult.NotFound -> Text("No definition found for \"${r.word}\".")
                        is LookupResult.Offline -> Text(
                            "Could not reach the dictionary. Check your internet connection. " +
                                    "Words you have looked up before work offline."
                        )
                        is LookupResult.Found -> Column {
                            Text(
                                r.entry.word,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            r.entry.phonetic?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                (r.note?.let { "$it. " } ?: "") +
                                        if (r.fromCache) "Saved on this device" else "Looked up online",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            r.entry.meanings.forEach { m ->
                                Text(
                                    m.partOfSpeech,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                                m.definitions.take(3).forEachIndexed { i, d ->
                                    Text(
                                        "${i + 1}. ${d.text}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    d.example?.let {
                                        Text(
                                            "\u201c$it\u201d",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontStyle = FontStyle.Italic,
                                            modifier = Modifier.padding(start = 16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (recent.isNotEmpty()) {
                        Text(
                            "Recent",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(recent) { w ->
                                AssistChip(onClick = { input = w; lookup(w) }, label = { Text(w) })
                            }
                        }
                    }
                }
            }
        }
    )
}