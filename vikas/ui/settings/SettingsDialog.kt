package com.example.vikas.ui.settings

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.vikas.data.backup.BackupManager
import com.example.vikas.data.settings.AppTheme
import com.example.vikas.data.settings.Settings
import com.example.vikas.data.settings.SettingsRepository
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val backup = remember { BackupManager(context) }
    val scope = rememberCoroutineScope()
    val saved by repo.settings.collectAsState(initial = Settings())
    var font by remember(saved.fontPercent) { mutableFloatStateOf(saved.fontPercent.toFloat()) }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var restoredBooks by remember { mutableStateOf<Int?>(null) }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = try {
                val s = backup.backup(uri)
                "Backup saved: ${s.books} books, ${s.bookmarks} bookmarks."
            } catch (e: Exception) {
                "Backup failed: ${e.message}"
            }
            busy = false
        }
    }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingRestore = uri }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                AppTheme.entries.forEach { t ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { scope.launch { repo.setTheme(t) } },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = saved.theme == t,
                            onClick = { scope.launch { repo.setTheme(t) } }
                        )
                        Text(t.label)
                    }
                }
                Text(
                    "EPUB text size: ${font.toInt()}%",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Slider(
                    value = font,
                    onValueChange = { font = it },
                    onValueChangeFinished = { scope.launch { repo.setFontPercent(font.toInt()) } },
                    valueRange = 80f..200f
                )

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text("Backup and restore", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Saves your library, progress, bookmarks, collections, statistics and settings. " +
                            "Book files are not included.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { createLauncher.launch("vikas-backup-${LocalDate.now()}.zip") }
                    ) { Text("Back up") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { openLauncher.launch(arrayOf("*/*")) }
                    ) { Text("Restore") }
                }
                if (busy) CircularProgressIndicator(Modifier.padding(top = 12.dp))
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    )

    // Confirmation before restore, because it replaces everything
    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore from backup?") },
            text = {
                Text(
                    "This replaces your current library data, bookmarks, collections, " +
                            "statistics and settings with the backup. VIKAS will close afterwards."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        busy = true
                        message = null
                        try {
                            restoredBooks = backup.restore(uri).books
                        } catch (e: Exception) {
                            message = "Restore failed: ${e.message}"
                        }
                        busy = false
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } }
        )
    }

    // After a restore the app must restart to reload the database
    restoredBooks?.let { n ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Restore complete") },
            text = { Text("$n books restored. VIKAS will close now. Open it again to finish.") },
            confirmButton = {
                TextButton(onClick = {
                    (context as? Activity)?.finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }) { Text("Close app") }
            }
        )
    }
}