package com.example.vikas.data.readers

import android.content.Context
import android.net.Uri

class TxtBookReader(context: Context, uri: Uri) : PagedTextReader(
    context.contentResolver.openInputStream(uri)!!.use {
        it.readBytes().toString(Charsets.UTF_8)
    }
)