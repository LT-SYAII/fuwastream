package com.fuwanime.data.manga

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import com.squareup.moshi.Moshi
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request

/** Comix (comix.ws), the primary English catalogue. API ids are prefixed so repository routing stays unambiguous. */
const val CX_PREFIX = "cx:"

fun isComixId(id: String): Boolean = id.startsWith(CX_PREFIX)

private const val COMIX_ORIGIN = "https://comix.ws"
private const val COMIX_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

private fun Any?.map(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
private fun Any?.list(): List<Any?> = this as? List<Any?> ?: emptyList()
private fun Map<String, Any?>.string(key: String): String? = this[key]?.toString()?.takeUnless { it == "null" }
private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt() ?: string(key)?.toDoubleOrNull()?.toInt()

/** Comix renames its minified JSON fields on some deploys, so every field is read through its known aliases. */
private fun Map<String, Any?>.firstString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { string(it)?.takeIf(String::isNotBlank) }
private fun Map<String, Any?>.firstInt(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { int(it) }
private fun Map<String, Any?>.tagNames(vararg keys: String): List<String> =
    keys.flatMap { this[it].list() }
        .mapNotNull { it.map().firstString("Z2", "k_", "rf", "title") }
        .distinct()

private fun absoluteComixUrl(path: String?): String? = path?.let { if (it.startsWith("http")) it else COMIX_ORIGIN + it }

internal fun comixManga(data: Any?): Manga {
    val m = data.map()
    val hid = m.firstString("rU", "SZ", "fH", "hid").orEmpty()
    val title = m.firstString("Z2", "k_", "rf", "title").orEmpty()
    val poster = m["P7"].map().ifEmpty { m["fL"].map() }.ifEmpty { m["Zs"].map() }.ifEmpty { m["poster"].map() }
    return Manga(
        id = CX_PREFIX + hid,
        title = title,
        coverUrl = absoluteComixUrl(poster.firstString("cT", "Tr", "sP", "HS", "QC", "Kq", "medium", "large")),
        description = m.firstString("Ht", "U$", "U7", "fK", "synopsis").orEmpty(),
        tags = m.tagNames("_v", "Tw", "QT", "Il", "Dx", "gS", "UR", "J5", "hV", "A5", "dE"),
        status = when (m.firstString("cB", "yx", "oF", "status")) {
            "releasing" -> MangaStatus.ONGOING
            "finished" -> MangaStatus.COMPLETED
            "on_hiatus" -> MangaStatus.HIATUS
            "discontinued" -> MangaStatus.CANCELLED
            else -> MangaStatus.UNKNOWN
        },
        year = m.firstInt("Hb", "Pi", "Pa", "_r", "year"),
        author = (m["TH"].list().ifEmpty { m["NR"].list() }).mapNotNull { it.map().firstString("Z2", "k_", "rf", "title") }
            .joinToString().ifBlank { null }
            ?: m["ZN"].list().ifEmpty { m["uy"].list() }.mapNotNull { it.map().firstString("Z2", "k_", "rf") }
                .joinToString().ifBlank { null },
        altTitles = m["Jy"].list().ifEmpty { m["Hv"].list() }.ifEmpty { m["Ga"].list() }
            .mapNotNull { it?.toString() }.filter { it != title },
        type = m.firstString("ei", "nv", "GR", "type")?.replaceFirstChar(Char::uppercase),
    )
}

internal fun comixChapter(data: Any?, hid: String): MangaChapter {
    val c = data.map()
    val id = c.firstInt("VT", "hk", "kM") ?: 0
    return MangaChapter(
        id = "$CX_PREFIX$hid:$id",
        number = c.firstString("PD", "Z_", "Xn")?.removeSuffix(".0"),
        title = c.firstString("q1", "nb", "f9")?.takeIf { it.isNotBlank() },
        language = c.firstString("Ax", "aK", "FP") ?: "en",
        group = c["qz"].map().ifEmpty { c["rk"].map() }.firstString("q1", "nb", "f9") ?: c["gm"].map().string("f9") ?: "Comix",
        pages = c.firstInt("QQ") ?: 1,
        externalUrl = null,
        publishAt = parseComixRelativeTime(c.firstString("iS")),
    )
}

internal fun comixPageUrls(data: Any?): List<String> {
    val m = data.map()
    return m["sN"].list().ifEmpty { m["iE"].list() }.ifEmpty { m["iT"].list() }
        .mapNotNull { it.map().firstString("FM", "Gh", "H$") }
        .filter { it.startsWith("https://") }
}

private fun parseComixRelativeTime(value: String?): Long {
    val match = Regex("(\\d+)\\s*(mos|mo|yr|yrs|y|w|d|h|m)").find(value ?: return 0L) ?: return 0L
    val count = match.groupValues[1].toLongOrNull() ?: return 0L
    val unit = match.groupValues[2]
    val ms = when (unit) {
        "m" -> 60_000L
        "h" -> 3_600_000L
        "d" -> 86_400_000L
        "w" -> 7 * 86_400_000L
        "mo", "mos" -> 30 * 86_400_000L
        "y", "yr", "yrs" -> 365 * 86_400_000L
        else -> return 0L
    }
    return System.currentTimeMillis() - count * ms
}

private fun comixParts(id: String): Pair<String, Int>? {
    val parts = id.removePrefix(CX_PREFIX).split(':', limit = 2)
    return if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: return null) else null
}

/**
 * Comix (comix.ws) client. The site signs and decrypts its API responses in a rotated client module, so a WebView
 * runs that module on-device and returns the decrypted JSON to Kotlin. No server or API key is involved.
 */
class ComixWsSource(
    app: Application,
    private val mature: suspend () -> Boolean = { false },
    private val client: OkHttpClient? = null,
) {
    private val runtime = ComixWebRuntime(app)
    private val moshi = Moshi.Builder().build()
    private val jsonAdapter = moshi.adapter(Any::class.java)

    suspend fun browse(
        order: MangaOrder,
        query: String = "",
        page: Int = 1,
        type: MangaType? = null,
        tagIds: List<String> = emptyList(),
        status: MangaStatus? = null,
    ): MangaPage {
        val params = linkedMapOf<String, Any?>(
            "page" to page,
            "limit" to PAGE_SIZE,
            "content_rating" to ratings(),
        )
        when (order) {
            MangaOrder.LATEST -> params["order"] = mapOf("chapter_updated_at" to "desc")
            MangaOrder.POPULAR -> params["order"] = mapOf("follows_total" to "desc")
            // Relevance is the API default for keyword searches; an explicit order could be rejected.
            MangaOrder.RELEVANCE -> if (query.isBlank()) params["order"] = mapOf("follows_total" to "desc")
        }
        query.trim().takeIf { it.isNotEmpty() }?.let { params["keyword"] = it }
        type?.let { params["types"] = listOf(it.slug) }
        tagIds.mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }?.let { params["genres_in"] = it }
        status?.comixValue()?.let { params["statuses"] = listOf(it) }
        val result = runtime.call("browse", params).map()
        val items = result["s3"].list().ifEmpty { result["I7"].list() }.ifEmpty { result["EY"].list() }
            .map(::comixManga).filter { it.id != CX_PREFIX && it.title.isNotBlank() }
        val meta = result["om"].map().ifEmpty { result["aY"].map() }.ifEmpty { result["\$U"].map() }
        val total = meta.firstInt("Yl", "T8", "fc") ?: items.size
        return MangaPage(items, total, (page - 1) * PAGE_SIZE)
    }

    suspend fun search(query: String): List<Manga> = browse(MangaOrder.RELEVANCE, query).items

    suspend fun detail(id: String): Manga {
        val hid = id.removePrefix(CX_PREFIX).substringBefore(':')
        return comixManga(runtime.call("detail", hid))
    }

    suspend fun chapters(id: String): List<MangaChapter> {
        val hid = id.removePrefix(CX_PREFIX).substringBefore(':')
        val all = ArrayList<MangaChapter>()
        var page = 1
        var lastPage = 1
        do {
            val result = runtime.call(
                "chapters",
                mapOf(
                    "hid" to hid,
                    "options" to mapOf("limit" to CHAPTER_LIMIT, "page" to page, "order" to mapOf("number" to "desc")),
                ),
            ).map()
            val items = result["s3"].list().ifEmpty { result["I7"].list() }.ifEmpty { result["EY"].list() }
            all += items.map { comixChapter(it, hid) }.filter { it.language == "en" || it.language.isBlank() }
            val meta = result["om"].map().ifEmpty { result["aY"].map() }.ifEmpty { result["\$U"].map() }
            val total = meta.firstInt("Yl", "T8", "fc") ?: items.size
            val perPage = meta.firstInt("wt", "f6") ?: CHAPTER_LIMIT
            val pages = meta.firstInt("zq") ?: if (perPage > 0) (total + perPage - 1) / perPage else 1
            lastPage = pages.coerceAtMost(MAX_CHAPTER_PAGES)
            page++
        } while (page <= lastPage)
        return pickChapters(all, "en")
    }

    suspend fun pages(chapterId: String): List<String> {
        val (_, id) = comixParts(chapterId) ?: return emptyList()
        return comixPageUrls(runtime.call("reader", id))
    }

    /** Newest chapter of recently updated Comix manga, for notifications and the home summary. */
    suspend fun latestOngoingChapters(limit: Int): List<MangaRelease> {
        val manga = ArrayList<Manga>()
        var page = 1
        while (manga.size < limit && page <= MAX_RELEASE_PAGES) {
            val items = browse(MangaOrder.LATEST, page = page).items
            if (items.isEmpty()) break
            manga += items
            page++
        }
        return coroutineScope {
            manga.take(limit).map { m ->
                async {
                    runCatching {
                        val hid = m.id.removePrefix(CX_PREFIX).substringBefore(':')
                        val detail = runtime.call("detail", hid).map()
                        val latestUrl = detail.firstString("HD", "latestChapterUrl") ?: return@runCatching null
                        val number = detail.firstInt("hb", "latestChapter")
                        val chapterId = Regex("/title/[^/]+/(\\d+)-chapter-").find(latestUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: return@runCatching null
                        val publishAt = parseComixRelativeTime(detail.firstString("sO", "chapterUpdatedAtFormatted"))
                        MangaRelease(m.id, m.title, "$CX_PREFIX$hid:$chapterId", number?.toString(), publishAt)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }

    suspend fun find(names: List<String>): Manga? {
        for (name in names.filter { it.isNotBlank() }.distinct().take(3)) {
            val hit = try {
                search(name)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }?.firstOrNull { sameTitle(name, it.title, it.altTitles) }
            if (hit != null) return hit
        }
        return null
    }

    /** Genre ids and names from Comix's server-rendered browse options; stable across client-module deploys. */
    suspend fun tags(): List<MangaTag> = withContext(Dispatchers.IO) {
        val http = client ?: return@withContext emptyList()
        val request = Request.Builder().url("$COMIX_ORIGIN/browse").header("User-Agent", COMIX_USER_AGENT).build()
        val html = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            response.body?.string().orEmpty()
        }
        val raw = INITIAL_DATA.find(html)?.groupValues?.getOrNull(1) ?: return@withContext emptyList()
        val root = jsonAdapter.fromJson(raw) as? Map<*, *> ?: return@withContext emptyList()
        val list = root["list"] as? Map<*, *> ?: return@withContext emptyList()
        val options = list["options"] as? Map<*, *> ?: return@withContext emptyList()
        (options["genres"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"]?.toString() ?: return@mapNotNull null
            val label = map["label"] as? String ?: return@mapNotNull null
            MangaTag(id, label, "genre")
        }
    }

    private suspend fun ratings(): List<String> =
        if (mature()) listOf("safe", "suggestive", "erotica") else listOf("safe", "suggestive")

    private companion object {
        const val PAGE_SIZE = 28
        const val CHAPTER_LIMIT = 100
        const val MAX_CHAPTER_PAGES = 50
        const val MAX_RELEASE_PAGES = 4
        val INITIAL_DATA = Regex("<script type=\"application/json\" id=\"initial-data\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
    }
}

private class ComixWebRuntime(private val app: Application) {
    private val moshi = Moshi.Builder().build()
    private val jsonAdapter = moshi.adapter(Any::class.java)
    private val mutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, Continuation<Any?>>()
    private var webView: WebView? = null
    private var pageReady = false
    private var pageContinuation: Continuation<Unit>? = null
    private var pageError: Throwable? = null

    suspend fun call(operation: String, argument: Any?): Any? = mutex.withLock {
        try {
            withTimeout(REQUEST_TIMEOUT_MS) { invoke(operation, argument) }
        } catch (e: TimeoutCancellationException) {
            // A caller's own timeout (e.g. a bounded search) is cancellation, not a Comix failure.
            if (!currentCoroutineContext().isActive) throw e
            throw IOException("Comix request timed out", e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun invoke(operation: String, argument: Any?): Any? = withContext(Dispatchers.Main.immediate) {
        ensurePage()
        suspendCancellableCoroutine { continuation ->
            val token = UUID.randomUUID().toString()
            pending[token] = continuation
            continuation.invokeOnCancellation { pending.remove(token) }
            val view = webView ?: throw IOException("Comix WebView unavailable")
            view.evaluateJavascript(script(token, operation, argument), null)
        }
    }

    /** Loads the site once; later calls reuse the page and the already-imported API module. */
    private suspend fun ensurePage() {
        if (pageReady) return
        pageError?.let { pageError = null; throw it }
        suspendCancellableCoroutine<Unit> { continuation ->
            val view = webView ?: WebView(app).also { created ->
                created.settings.javaScriptEnabled = true
                created.settings.domStorageEnabled = true
                created.settings.userAgentString = COMIX_USER_AGENT
                created.addJavascriptInterface(Bridge(), BRIDGE_NAME)
                webView = created
            }
            pageContinuation = continuation
            continuation.invokeOnCancellation { if (pageContinuation === continuation) pageContinuation = null }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!url.startsWith(COMIX_ORIGIN)) return
                    pageReady = true
                    pageContinuation?.resume(Unit)
                    pageContinuation = null
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    pageReady = false
                    val failure = IOException("Comix WebView ${error.errorCode}: ${error.description}")
                    pageError = failure
                    pageContinuation?.resumeWithException(failure)
                    pageContinuation = null
                }
            }
            view.loadUrl("$COMIX_ORIGIN/browse")
        }
    }

    private fun script(token: String, operation: String, argument: Any?): String {
        val arg = jsonAdapter.toJson(argument)
        return """
            (async () => {
              const token = ${jsString(token)};
              try {
                let api = window.__fuwaComixApi;
                if (!api) {
                  const main = [...document.scripts].map(s => s.src).find(s => /\/main-[^/]+\.js(?:\?|$)/.test(s));
                  if (!main) throw new Error('Comix main module not found');
                  const source = await (await fetch(main)).text();
                  const match = source.match(/apicore-[A-Za-z0-9_-]+\.js/);
                  if (!match) throw new Error('Comix API module not found');
                  api = await import(new URL(match[0], main).href);
                  window.__fuwaComixApi = api;
                }
                const groups = Object.values(api).filter(v => v && typeof v === 'object');
                const find = (test) => {
                  for (const group of groups) {
                    try { if (test(group)) return group; } catch (_) {}
                  }
                  return null;
                };
                // The site's minified export letters change on deploy; find modules by the methods they expose.
                const catalogue = find(g => ['QJ', 'Tm', 'OJ', 'kC', 'Aj', 'cr', 'IX', 'zf'].every(n => typeof g[n] === 'function'));
                if (!catalogue) throw new Error('Comix catalogue module not found');
                const op = ${jsString(operation)};
                const arg = $arg;
                let result;
                if (op === 'browse') {
                  result = await catalogue.QJ(arg);
                } else if (op === 'detail') {
                  result = await catalogue.Tm(arg);
                } else if (op === 'chapters') {
                  result = await catalogue.OJ(arg.hid, arg.options);
                } else if (op === 'reader') {
                  const chapter = find(g => typeof g.Tm === 'function' && typeof g.tA === 'function' && typeof g.QJ !== 'function');
                  if (!chapter) throw new Error('Comix chapter module not found');
                  result = await chapter.Tm(arg);
                } else {
                  throw new Error('Unknown Comix operation');
                }
                window.$BRIDGE_NAME.success(token, JSON.stringify(result));
              } catch (error) {
                window.$BRIDGE_NAME.failure(token, String(error && (error.stack || error.message) || error));
              }
            })();
        """.trimIndent()
    }

    private fun jsString(value: String): String = jsonAdapter.toJson(value)

    private fun fail(token: String, error: Throwable) {
        pending.remove(token)?.resumeWithException(error)
    }

    @Keep
    private inner class Bridge {
        @JavascriptInterface
        fun success(token: String, json: String) = handler.post {
            val continuation = pending.remove(token) ?: return@post
            runCatching { jsonAdapter.fromJson(json) }
                .onSuccess { continuation.resume(it) }
                .onFailure { continuation.resumeWithException(it) }
        }

        @JavascriptInterface
        fun failure(token: String, message: String) = handler.post {
            fail(token, IOException(message.take(1_000)))
        }
    }

    private companion object {
        const val BRIDGE_NAME = "FuwaComix"
        const val REQUEST_TIMEOUT_MS = 60_000L
    }
}
