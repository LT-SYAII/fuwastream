package com.fuwanime.data.manga

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Text recognised on one page and translated to Indonesian. */
data class PageTranslation(val text: String, val blocks: Int)

/**
 * On-device English → Indonesian translation for image chapters (Comix). OCR runs with ML Kit's bundled Latin
 * recogniser; the translation model is downloaded once and then works offline. Nothing leaves the device.
 */
class MangaTranslator(
    private val http: OkHttpClient,
) {
    private val mutex = Mutex()
    private val clientLock = Any()
    private var translatorClient: Translator? = null
    private var recognizerClient: TextRecognizer? = null

    @Volatile
    private var modelReady = false

    private fun translator(): Translator = synchronized(clientLock) {
        translatorClient ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.INDONESIAN)
                .build(),
        ).also { translatorClient = it }
    }

    private fun recognizer(): TextRecognizer = synchronized(clientLock) {
        recognizerClient ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .also { recognizerClient = it }
    }

    /**
     * Downloads the page, reads its text and translates it. [onDownloadingModel] fires only while the one-time
     * model download is in progress, so the reader can show its status.
     */
    suspend fun translatePage(url: String, onDownloadingModel: () -> Unit = {}): PageTranslation = mutex.withLock {
        val bytes = download(url)
        val recognized = recognize(bytes)
        if (recognized.isBlank()) return@withLock PageTranslation("", 0)
        if (!modelReady) {
            onDownloadingModel()
            translator().downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            modelReady = true
        }
        val translated = translationChunks(recognized).map { translator().translate(it).await() }
        PageTranslation(translated.joinToString("\n"), recognized.lines().count { it.isNotBlank() })
    }

    /** Releases the ML Kit clients; the app-wide instance normally lives for the whole process. */
    fun close() {
        synchronized(clientLock) {
            runCatching { translatorClient?.close() }
            runCatching { recognizerClient?.close() }
            translatorClient = null
            recognizerClient = null
            modelReady = false
        }
    }

    private suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        // Downloaded chapters keep their pages as file: URIs; read those directly.
        if (url.startsWith("file:")) {
            val path = Uri.parse(url).path ?: throw IOException("Invalid local page: $url")
            return@withContext File(path).readBytes()
        }
        // The Comix CDN rejects requests that carry a Referer (403), so send none.
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Comix image HTTP ${response.code}")
            response.body?.bytes() ?: throw IOException("Comix image is empty")
        }
    }

    /** OCR for one image, split into vertical tiles so very long webtoon strips stay within memory limits. */
    private suspend fun recognize(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) throw IOException("Unsupported Comix image")

        val blocks = ArrayList<String>()
        if (height <= TILE_HEIGHT) {
            val bitmap = decode(bytes, width, height) ?: throw IOException("Unsupported Comix image")
            try {
                addBlocks(blocks, recognizeBitmap(bitmap))
            } finally {
                bitmap.recycle()
            }
        } else {
            val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                ?: throw IOException("Unsupported Comix image")
            try {
                var top = 0
                while (top < height) {
                    val bottom = (top + TILE_HEIGHT).coerceAtMost(height)
                    val tile = decoder.decodeRegion(Rect(0, top, width, bottom), BitmapFactory.Options())
                    if (tile != null) {
                        try {
                            addBlocks(blocks, recognizeBitmap(tile))
                        } finally {
                            tile.recycle()
                        }
                    }
                    if (bottom >= height) break
                    // Overlap keeps a bubble cut by the tile edge readable in one of the two tiles.
                    top = bottom - TILE_OVERLAP
                }
            } finally {
                decoder.recycle()
            }
        }
        blocks.joinToString("\n")
    }

    private fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        var sample = 1
        while (width / sample > MAX_WIDTH || (width / sample).toLong() * (height / sample) > MAX_PIXELS) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private suspend fun recognizeBitmap(bitmap: Bitmap): List<String> {
        val result = recognizer().process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.mapNotNull { block ->
            block.text.trim().takeIf { text -> text.count(Char::isLetterOrDigit) >= 2 }
        }
    }

    /** Drops the duplicate block produced when a bubble sits on the overlap between two tiles. */
    private fun addBlocks(target: MutableList<String>, blocks: List<String>) {
        for (block in blocks) {
            val key = block.lowercase().replace(WHITESPACE, " ").trim()
            if (key.isEmpty()) continue
            if (target.takeLast(4).any { it.lowercase().replace(WHITESPACE, " ").trim() == key }) continue
            target += block
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        const val TILE_HEIGHT = 2_400
        const val TILE_OVERLAP = 120
        const val MAX_WIDTH = 2_000
        const val MAX_PIXELS = 8_000_000L
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Splits OCR text at line boundaries for the translator, which caps a single request at a few thousand characters.
 * Internal for unit tests: the split must never drop or reorder a line.
 */
internal fun translationChunks(text: String, limit: Int = 3_000): List<String> {
    if (text.length <= limit) return listOf(text)
    val chunks = ArrayList<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotBlank()) chunks += current.toString().trim()
        current.clear()
    }
    for (line in text.lineSequence()) {
        if (line.length > limit) {
            flush()
            var start = 0
            while (start < line.length) {
                val end = (start + limit).coerceAtMost(line.length)
                chunks += line.substring(start, end)
                start = end
            }
            continue
        }
        if (current.isNotEmpty() && current.length + line.length + 1 > limit) flush()
        if (current.isNotEmpty()) current.append('\n')
        current.append(line)
    }
    flush()
    return chunks
}
