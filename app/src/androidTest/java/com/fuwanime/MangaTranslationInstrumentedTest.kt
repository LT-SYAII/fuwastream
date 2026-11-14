package com.fuwanime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fuwanime.data.manga.MangaRelease
import com.fuwanime.data.manga.MangaRepository
import com.fuwanime.data.manga.PageTranslation
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real pipeline on a device: network download → bundled ML Kit OCR → EN→ID translation.
 * It needs network access and the Google APIs emulator image (the translation model is downloaded once).
 * CI runs it through `connectedDebugAndroidTest`; see the `translate` job in build.yml.
 */
@RunWith(AndroidJUnit4::class)
class MangaTranslationInstrumentedTest {
    @Test(timeout = 30 * 60 * 1000L)
    fun translatesEveryPageOfOneComixChapter() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<FuwaApp>()
        val translator = app.container.mangaTranslator
        val repository = app.container.mangaRepository

        val font = Typeface.createFromAsset(app.assets, TRANSLATION_FONT)
        println("translation font loaded: $font")

        val releases = repository.latestOngoingChapters(limit = 8)
        assertTrue("Comix returned no recent chapters", releases.isNotEmpty())

        val (release, pages) = firstReadableChapter(repository, releases)
        assertTrue("No readable pages in ${release.title} ${release.chapterId}", pages.isNotEmpty())
        println("chapter: ${release.title} ${release.chapterId} (${pages.size} pages)")

        val translations = ArrayList<PageTranslation>(pages.size)
        pages.forEachIndexed { index, url ->
            val started = System.currentTimeMillis()
            val translation = translator.translatePage(url) {
                println("downloading the translation model (page ${index + 1})")
            }
            val elapsed = System.currentTimeMillis() - started
            println(
                "page ${index + 1}/${pages.size}: ${translation.regions.size} regions, " +
                    "${translation.text.length} chars in ${elapsed}ms: " +
                    translation.text.take(180).replace('\n', ' '),
            )
            translations += translation
        }

        val withText = translations.count { it.text.isNotBlank() }
        val withRegions = translations.count { it.regions.isNotEmpty() }
        val totalChars = translations.sumOf { it.text.length }
        // Save the first three translated pages with the real overlay font; CI pulls this folder as evidence.
        // Done before the assertions so a failing run still produces images to inspect.
        var saved = 0
        translations.forEachIndexed { index, translation ->
            if (saved < 3 && translation.regions.isNotEmpty()) {
                runCatching { writeEvidence(app, pages[index], translation, index + 1) }
                    .onSuccess { saved++ }
                    .onFailure { println("could not save evidence for page ${index + 1}: ${it.message}") }
            }
        }
        println("translated $withText/${pages.size} pages, $withRegions with regions, $totalChars chars total")
        println("saved $saved translated page images")
        assertTrue("OCR produced no text on any page", withRegions > 0)
        // Webtoon chapters contain genuinely textless art pages; require text on a meaningful share instead.
        assertTrue("OCR text missing on most pages ($withText/${pages.size})", withText >= maxOf(1, pages.size / 4))
        assertTrue("Translated text is suspiciously short ($totalChars chars)", totalChars >= pages.size * 20)
        val joined = translations.joinToString(" ") { it.text }.lowercase()
        assertTrue(
            "Translation does not look Indonesian: ${joined.take(400)}",
            INDONESIAN_WORDS.any { Regex("\\b$it\\b").containsMatchIn(joined) },
        )
        translator.close()
    }

    /** Draws the translated blocks with [TRANSLATION_FONT] so the artifact proves both OCR and the overlay font. */
    private fun writeEvidence(app: FuwaApp, url: String, translation: PageTranslation, page: Int) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val bytes = app.container.httpClient.newCall(request).execute().use { it.body?.bytes() } ?: return
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        try {
            val canvas = Canvas(bitmap)
            val typeface = Typeface.createFromAsset(app.assets, TRANSLATION_FONT)
            for (block in translation.regions) {
                val left = block.left * bitmap.width
                val top = block.top * bitmap.height
                val right = block.right * bitmap.width
                val bottom = block.bottom * bitmap.height
                canvas.drawRect(left, top, right, bottom, Paint().apply { color = block.background })
                val width = (right - left - 8).toInt().coerceAtLeast(16)
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = block.foreground
                    this.typeface = typeface
                    textSize = ((bottom - top) * 0.55f).coerceIn(12f, 72f)
                }
                val layout = StaticLayout.Builder
                    .obtain(block.text, 0, block.text.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build()
                canvas.save()
                canvas.translate(left + 4, top + 4)
                layout.draw(canvas)
                canvas.restore()
            }
            val dir = File(app.getExternalFilesDir(null), "translation-evidence").apply { mkdirs() }
            FileOutputStream(File(dir, "page-%02d.png".format(page))).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** The newest release can be locked or empty, so try a few until one exposes page images. */
    private suspend fun firstReadableChapter(
        repository: MangaRepository,
        releases: List<MangaRelease>,
    ): Pair<MangaRelease, List<String>> {
        for (release in releases) {
            val pages = runCatching { repository.pages(release.chapterId) }.getOrElse {
                println("pages failed for ${release.title} ${release.chapterId}: ${it.message}")
                emptyList()
            }
            // A locked chapter usually resolves to a single placeholder image; skip it for a real chapter.
            if (pages.size >= 3) return release to pages
            println("no readable pages for ${release.title} ${release.chapterId} (${pages.size} pages)")
        }
        throw AssertionError("None of ${releases.size} recent Comix chapters exposed page images")
    }

    private companion object {
        const val TRANSLATION_FONT = "translation_font.ttf"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

        /** Broad markers; English text cannot contain most of these, so this proves EN→ID actually ran. */
        val INDONESIAN_WORDS = listOf(
            "yang", "dan", "aku", "kamu", "kau", "tidak", "dengan", "untuk", "ini", "itu",
            "adalah", "apa", "kita", "kami", "kalian", "mereka", "dia", "sudah", "akan", "bisa",
            "dari", "ke", "saya", "anda", "tapi", "karena", "jika", "saat", "harus", "sedang",
            "telah", "belum", "bukan", "hanya", "juga", "lagi",
        )
    }
}
