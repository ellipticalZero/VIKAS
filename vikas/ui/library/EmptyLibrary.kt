package com.example.vikas.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun EmptyLibrary(
    modifier: Modifier = Modifier,
    scanning: Boolean,
    lastScanCount: Int?,
    hiddenCount: Int,
    onChooseFolder: () -> Unit,
    onAddOnline: () -> Unit,
    onSettings: () -> Unit,
    onRestoreRemoved: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BookIllustration()
        Spacer(Modifier.height(24.dp))

        if (scanning) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                "Looking for books...",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                "This can take a moment for large folders.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Text(
                "Your library is empty",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                "Add books from your phone, or download free ones online.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            // The last folder that was scanned had nothing VIKAS can open
            if (lastScanCount == 0) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Text(
                        "No books were found in that folder. VIKAS looks for .epub, .pdf, .cbz " +
                                "and .txt files, including inside subfolders. Try a different folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Button(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth()) {
                Text("Choose books folder")
            }
            OutlinedButton(
                onClick = onAddOnline,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("Browse free books online")
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 20.dp)
            ) {
                listOf("EPUB", "PDF", "CBZ", "TXT").forEach { FormatLabel(it) }
            }

            if (hiddenCount > 0) {
                TextButton(onClick = onRestoreRemoved, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Restore $hiddenCount removed books")
                }
            }
            TextButton(onClick = onSettings) { Text("Settings and restore from backup") }
        }
    }
}

@Composable
private fun FormatLabel(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

// The same open book and bookmark ribbon as the app icon, drawn in code
@Composable
private fun BookIllustration(modifier: Modifier = Modifier) {
    Canvas(modifier.size(120.dp)) {
        val s = size.width / 108f
        drawRoundRect(Color(0xFF0B2545), cornerRadius = CornerRadius(28.dp.toPx()))

        val leftPage = Path().apply {
            moveTo(52f, 39f); cubicTo(46f, 35f, 38f, 33f, 30f, 34f)
            lineTo(30f, 70f); cubicTo(38f, 69f, 46f, 71f, 52f, 75f); close()
        }
        val rightPage = Path().apply {
            moveTo(56f, 39f); cubicTo(62f, 35f, 70f, 33f, 78f, 34f)
            lineTo(78f, 70f); cubicTo(70f, 69f, 62f, 71f, 56f, 75f); close()
        }
        val ribbon = Path().apply {
            moveTo(65f, 34.3f); lineTo(65f, 50f); lineTo(69.5f, 46f)
            lineTo(74f, 50f); lineTo(74f, 33.7f); close()
        }
        scale(s, pivot = Offset.Zero) {
            scale(1.2f, pivot = Offset(54f, 54f)) {
                drawPath(leftPage, Color(0xFFF4ECD8))
                drawPath(rightPage, Color(0xFFF4ECD8))
                drawPath(ribbon, Color(0xFFE0A030))
            }
        }
    }
}