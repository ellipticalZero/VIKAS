package com.example.vikas

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.vikas.data.db.BookEntity
import com.example.vikas.data.readers.BookReaders
import com.example.vikas.data.settings.AppTheme
import com.example.vikas.data.settings.LocalReaderStyle
import com.example.vikas.data.settings.ReaderStyle
import com.example.vikas.data.settings.Settings
import com.example.vikas.data.settings.SettingsRepository
import com.example.vikas.ui.library.LibraryScreen
import com.example.vikas.ui.reader.ReaderScreen
import com.example.vikas.ui.theme.VIKASTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Let content extend into the camera notch area when bars are hidden
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContent {
            val context = LocalContext.current
            val repo = remember { SettingsRepository(context) }
            val settings by repo.settings.collectAsState(initial = Settings())
            val theme = settings.theme.resolve(isSystemInDarkTheme())

            CompositionLocalProvider(
                LocalReaderStyle provides ReaderStyle(theme, settings.fontPercent)
            ) {
                VIKASTheme(darkTheme = theme == AppTheme.DARK) {
                    var openBook by remember { mutableStateOf<BookEntity?>(null) }

                    // Full screen (hide status + navigation bars) only while reading
                    val reading = openBook != null
                    DisposableEffect(reading) {
                        val w = (context as Activity).window
                        val controller = WindowCompat.getInsetsController(w, w.decorView)
                        if (reading) {
                            controller.systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            controller.hide(WindowInsetsCompat.Type.systemBars())
                        } else {
                            controller.show(WindowInsetsCompat.Type.systemBars())
                        }
                        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
                    }

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        contentWindowInsets = if (reading) WindowInsets(0, 0, 0, 0)
                        else ScaffoldDefaults.contentWindowInsets
                    ) { innerPadding ->
                        val book = openBook
                        if (book == null) {
                            LibraryScreen(
                                modifier = Modifier.padding(innerPadding),
                                onOpen = {
                                    if (it.format in BookReaders.supportedFormats) openBook = it
                                    else Toast.makeText(
                                        context, "${it.format.uppercase()} reader coming soon",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        } else {
                            ReaderScreen(
                                book = book,
                                onBack = { openBook = null },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }
}