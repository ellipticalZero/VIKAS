package com.example.vikas.data.readers

import android.graphics.Bitmap
import android.util.LruCache

class PageCache(
    maxBytes: Int = (Runtime.getRuntime().maxMemory() / 8).toInt()
) {
    private val cache = object : LruCache<Int, Bitmap>(maxBytes) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    fun get(page: Int): Bitmap? = cache.get(page)
    fun put(page: Int, bmp: Bitmap) { cache.put(page, bmp) }
}