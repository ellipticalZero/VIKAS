# VIKAS

**A unified, lightweight ebook reader for Android.** One app for **EPUB, PDF, CBZ and TXT**, built with Kotlin and Jetpack Compose, and designed around core operating-system ideas: scoped file access, demand paging with an LRU cache, on-demand ZIP handling, transactional persistence and safe concurrency.

> Course project for **MACSE512 Operating Systems**, School of Computer Science and Engineering, VIT. Team: **Code × Writers**.

## Screenshots

<!-- Add your screenshots to docs/screenshots and update the file names below -->
| Library | PDF reader | EPUB reader | Highlights |
|---|---|---|---|
| ![Library](docs/screenshots/library.png) | ![PDF](docs/screenshots/pdf.png) | ![EPUB](docs/screenshots/epub.png) | ![Highlights](docs/screenshots/highlights.png) |

## Features

**Reading**
- Four formats through one reader: EPUB, PDF, CBZ (comics) and TXT
- Light, dark and sepia themes, adjustable EPUB text size, full-screen reading (tap to show or hide controls)
- Reading progress saved on every page; books reopen where you stopped
- Bookmarks with notes, and text highlights (four colours, optional notes) for EPUB and TXT
- Read aloud with speed control (EPUB, TXT and PDFs that contain text)
- Dictionary with an on-device cache, so words you have looked up work offline

**Library**
- Pick a folder once; VIKAS finds supported books, including in subfolders
- Titles, authors and cover thumbnails extracted in the background
- Search by title or author; in-book search for EPUB, TXT and PDF text
- Collections (add several books at once), remove from library and restore
- Reading statistics: time, streak, a 7-day chart and most-read books

**Online and data**
- Add books from **OPDS catalogs** (for example Project Gutenberg, or your own Calibre server) or from a direct file link
- Backup and restore of your library data, bookmarks, notes, highlights, collections, statistics and settings as a single ZIP file

## Operating-system concepts

| Concept | Where it appears |
|---|---|
| File management | Storage Access Framework with a persisted folder permission; no all-files access; private storage for downloads and covers |
| Memory management | Demand paging of pages, an LRU cache limited to 1/8 of the heap and measured in bytes, prefetch of neighbouring pages, disk-backed PDF parsing |
| Compression and archives | CBZ and EPUB opened with ZIP random access and decompressed on demand; backups written as ZIP files |
| Persistence | Room (SQLite) with 7 tables and 5 schema migrations, transactions for multi-step changes, DataStore for settings |
| Concurrency | Kotlin coroutines on IO and Default dispatchers, mutexes around non-thread-safe resources, lifecycle-aware tasks |
| Networking and IPC | HTTP with manual redirects, validated downloads, text-to-speech engine running in another process |
| Security | Minimal permissions, scripts inside EPUBs blocked by a Content-Security-Policy, whitelisted ZIP entries on restore |

## Architecture

```
UI layer (Jetpack Compose)
  Library screen · Reader screen · Online catalogs · Dialogs
            │
Business logic
  BookReader (PDF | CBZ | TXT | EPUB) · FileScanner · MetadataExtractor
  OpdsClient · DictionaryRepository · TtsController · SessionTracker · BackupManager
            │
Data layer
  Room database · DataStore · Storage Access Framework · private storage
```

Every format implements one small interface, so the reader screen, progress, bookmarks, notes and statistics work the same for all of them:

```kotlin
interface BookReader : Closeable {
    val pageCount: Int
    suspend fun renderPage(index: Int, targetWidth: Int): Bitmap
}
```

EPUB chapters are shown in a WebView whose requests are answered directly from the ZIP file, so nothing is extracted to disk.

## Tech stack

| | |
|---|---|
| Language and UI | Kotlin 2.2, Jetpack Compose, Material 3 |
| Build | Android Gradle Plugin 9.4, Gradle (Kotlin DSL), KSP |
| Database and settings | Room 2.8, DataStore Preferences |
| Parsing | Jsoup (EPUB, OPDS, HTML text) |
| PDF text | PdfBox-Android |
| Platform | Android 8.0+ (minSdk 26) |

## Project structure

```
app/src/main/java/com/example/vikas/
├── MainActivity.kt
├── data/
│   ├── db/          Room entities, DAOs, migrations
│   ├── scan/        FileScanner, MetadataExtractor
│   ├── readers/     BookReader implementations, PageCache, search, PDF text
│   ├── settings/    DataStore preferences
│   ├── stats/       Reading session tracking
│   ├── tts/         Text-to-speech wrapper
│   ├── dictionary/  Dictionary lookup with cache
│   ├── backup/      Backup and restore
│   └── opds/        OPDS parser, HTTP client, downloads
└── ui/
    ├── library/     Library screens
    ├── reader/      Reader screen, EPUB view, dialogs
    ├── settings/    ui/stats/    ui/online/    ui/theme/
```

## Getting started

**Requirements:** Android Studio (a version that supports Android Gradle Plugin 9.4), JDK 11 or newer, and a phone or emulator running Android 8.0+.

1. Clone the repository
   ```bash
   git clone https://github.com/<your-username>/VIKAS.git
   ```
2. Open the project in Android Studio and wait for the Gradle sync to finish.
3. Connect a phone with USB debugging on, or start an emulator, then press **Run**.

**Build an APK:** *Build → Generate App Bundles or APKs → Generate APKs*. The debug APK is created in `app/build/outputs/apk/debug/`.

## Using the app

1. On the library screen, tap **Choose books folder** and pick a folder that contains your books. (Android 11+ does not allow the Downloads folder itself, so use a subfolder.)
2. Tap a book to read. Tap the page to show or hide the controls. Use **More** for search, notes, highlights, dictionary and bookmarks.
3. To highlight in an EPUB, long-press a word, drag to select, and tap **Highlight**. In a TXT file, long-press, drag, then tap **Highlight**.
4. To add books online, tap **Add online** and paste an OPDS catalog address (for example `https://m.gutenberg.org/ebooks.opds/`) or a direct link to a book file.
5. Use **Settings** for themes, text size, backup and restore.

## Permissions and privacy

- Only the **INTERNET** permission is requested (dictionary and online catalogs). There is no storage permission; folders are opened through the system picker.
- No account, analytics or advertising. Your books, notes and statistics stay on your device.
- Plain `http://` is allowed so catalogs on a home network (for example Calibre) work. Public catalogs use HTTPS.

## Known limitations

- Highlights are available for EPUB and TXT only, not for PDF or CBZ.
- Scanned PDFs contain no text, so search and read aloud need OCR (not implemented).
- Only OPDS 1.x catalogs and DRM-free books are supported.
- Read aloud stops when the app leaves the foreground.
- The text-size setting applies to EPUB only.
- Backups contain library data, not the book files themselves.

## Roadmap

- Highlights for PDF and comics
- OCR for scanned PDFs
- OPDS 2.0 support
- Background audio playback with media controls
- Library sync between devices and scheduled backups
- Release build with shrinker rules, and automated tests

## Team

**Code × Writers**: Bandaru Karthikeya, Rajendran Shravan Sundar, Pavurayala Vikram

## Acknowledgements

[Jetpack Compose](https://developer.android.com/jetpack/compose), [Room](https://developer.android.com/training/data-storage/room), [Jsoup](https://jsoup.org), [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android), the [OPDS specification](https://opds-spec.org), [Project Gutenberg](https://www.gutenberg.org), the [Free Dictionary API](https://dictionaryapi.dev) and [Wiktionary](https://en.wiktionary.org) (content under CC BY-SA).

## License

<!-- Choose a licence (for example MIT) and add a LICENSE file to the repository -->
