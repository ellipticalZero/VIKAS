package com.example.vikas.data.readers

import android.graphics.Bitmap
import java.io.Closeable

interface BookReader : Closeable {
    val pageCount: Int
    suspend fun renderPage(index: Int, targetWidth: Int): Bitmap
}