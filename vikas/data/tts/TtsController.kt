package com.example.vikas.data.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

// Wraps Android's TextToSpeech. Create, call and shutdown on the main thread.
class TtsController(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var generation = 0          // bumps on every speak/stop, to ignore stale callbacks
    private var lastId = ""             // id of the final chunk of the current request
    private var queued: (() -> Unit)? = null

    var onFinished: (() -> Unit)? = null   // all chunks of the last speak() were spoken
    var onProblem: ((String) -> Unit)? = null

    var rate: Float = 1.0f
        set(value) {
            field = value
            tts?.setSpeechRate(value)
        }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            // The engine starts in another process; this callback comes back asynchronously
            main.post {
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    onProblem?.invoke("Text-to-speech is not available on this device.")
                    return@post
                }
                var result = engine.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    result = engine.setLanguage(Locale.US)
                }
                engine.setSpeechRate(rate)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == lastId) main.post { onFinished?.invoke() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == lastId) {
                            main.post { onProblem?.invoke("Speech failed. Check your text-to-speech settings.") }
                        }
                    }
                })
                ready = true
                queued?.invoke()
                queued = null
            }
        }
    }

    fun speak(text: String) {
        if (!ready) {
            queued = { doSpeak(text) }   // engine still starting: speak as soon as it is ready
            return
        }
        doSpeak(text)
    }

    private fun doSpeak(text: String) {
        val engine = tts ?: return
        val gen = ++generation
        val chunks = chunk(text, TextToSpeech.getMaxSpeechInputLength())
        if (chunks.isEmpty()) {
            lastId = ""
            main.post { if (gen == generation) onFinished?.invoke() } // blank page: move on
            return
        }
        lastId = "g$gen-${chunks.lastIndex}"
        chunks.forEachIndexed { i, c ->
            engine.speak(
                c,
                if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "g$gen-$i"
            )
        }
    }

    fun stop() {
        generation++
        lastId = ""
        queued = null
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    // Splits text into pieces below the engine's input limit, at sentence boundaries
    private fun chunk(text: String, maxLen: Int): List<String> {
        val limit = (maxLen - 100).coerceIn(500, 3000)
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()

        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (sentence in clean.split(Regex("(?<=[.!?])\\s+"))) {
            var s = sentence
            while (s.length > limit) {   // one huge sentence: cut at a space
                val cut = s.lastIndexOf(' ', limit).let { if (it <= 0) limit else it }
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                out += s.substring(0, cut)
                s = s.substring(cut).trim()
            }
            if (s.isEmpty()) continue
            if (sb.isNotEmpty() && sb.length + s.length + 1 > limit) {
                out += sb.toString()
                sb.clear()
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}