package com.example.vikas.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import com.example.vikas.data.settings.AppTheme
import com.example.vikas.data.settings.LocalReaderStyle

// Dark: invert colors (white page becomes black, black text becomes white)
private val INVERT = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

// Sepia: scales a white page to the cream background color (#F4ECD8)
private val SEPIA = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.957f, 0f, 0f, 0f, 0f,
            0f, 0.925f, 0f, 0f, 0f,
            0f, 0f, 0.847f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

// CBZ is comic art, so it is never filtered
@Composable
fun pageFilter(format: String): ColorFilter? {
    if (format == "cbz") return null
    return when (LocalReaderStyle.current.theme) {
        AppTheme.DARK -> INVERT
        AppTheme.SEPIA -> SEPIA
        else -> null
    }
}

@Composable
fun readerBackground(): Color = when (LocalReaderStyle.current.theme) {
    AppTheme.DARK -> Color.Black
    AppTheme.SEPIA -> Color(0xFFF4ECD8)
    else -> Color.White
}