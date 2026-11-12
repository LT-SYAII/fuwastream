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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Comix (comix.ws), an English catalogue. API ids are prefixed so repository routing stays unambiguous. */
const val CX_PREFIX = "cx:"

fun isComixId(id: String): Boolean = id.startsWith(CX_PREFIX)

private const val COMIX_ORIGIN = "https://comix.ws"

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
        .mapNotNull { it.map().firstString("k_", "rf") }
        .distinct()

private fun absoluteComixUrl(path: String?): String? = path?.let { if (it.startsWith("http")) it else COMIX_ORIGIN + it }

internal fun comixManga(data: Any?): Manga {
    val m = data.map()
    val hid = m.firstString("SZ", "fH").orEmpty()
    val title = m.firstString("k_", "rf").orEmpty()
    val poster = m["fL"].map().ifEmpty { m["Zs"].map() }
    return Manga(
        id = CX_PREFIX + hid,
        title = title,
        coverUrl = absoluteComixUrl(poster.firstString("Tr", "sP", "HS", "QC")),
        description = m.firstString("U$", "U7", "fK").orEmpty(),
        tags = m.tagNames("Dx", "gS", "UR", "J5", "hV", "A5", "dE"),
        status = when (m.firstString("yx", "oF")) {
            "releasing" -> MangaStatus.ONGOING
            "finished" -> MangaStatus.COMPLETED
            "on_hiatus" -> MangaStatus.HIATUS
            "discontinued" -> MangaStatus.CANCELLED
            else -> MangaStatus.UNKNOWN
        },
        year = m.firstInt("Pi", "Pa", "_r"),
        author = m["ZN"].list().ifEmpty { m["uy"].list() }
            .mapNotNull { it.map().firstString("k_", "rf") }.joinToString().ifBlank { null },
        altTitles = m["Hv"].list().ifEmpty { m["Ga"].list() }
            .mapNotNull { it?.toString() }.filter { it != title },
        type = m.firstString("nv", "GR")?.replaceFirstChar(Char::uppercase),
    )
}

internal fun comixChapter(data: Any?, hid: String): MangaChapter {
    val c = data.map()
    val id = c.firstInt("hk", "kM") ?: 0
    return MangaChapter(
        id = "$CX_PREFIX$hid:$id",
        number = c.firstString("Z_", "Xn")?.removeSuffix(".0"),
        title = c.firstString("nb", "f9"),
        language = c.firstString("aK", "FP") ?: "en",
        group = c["rk"].map().firstString("nb", "f9") ?: c["gm"].map().string("f9") ?: "Comix",
        // Comix only reports the exact page count on the reader endpoint. One marks API chapters as readable.
        pages = 1,
        externalUrl = null,
        publishAt = 0,
    )
}

internal fun comixPageUrls(data: Any?): List<String> {
    val m = data.map()
    return m["iE"].list().ifEmpty { m["iT"].list() }
        .mapNotNull { it.map().firstString("Gh", "H$") }
        .filter { it.startsWith("https://") }
}

private fun comixParts(id: String): Pair<String, Int>? {
    val parts = id.removePrefix(CX_PREFIX).split(':', limit = 2)
    return if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: return null) else null
}

/**
 * Runs Comix's current signing/decryption module in Android WebView. This remains entirely on-device: the WebView
 * talks directly to comix.ws and only returns the decrypted JSON to Kotlin. Keeping the site's module dynamic also
 * avoids shipping its frequently rotated cfg token or an obsolete copy of the protected algorithm.
 */
class ComixWsSource(
    app: Application,
    private val mature: suspend () -> Boolean = { false },
) {
    private val runtime = ComixWebRuntime(app)

    suspend fun browse(order: MangaOrder, query: String = "", page: Int = 1, type: MangaType? = null): MangaPage {
        val params = linkedMapOf<String, Any?>(
            "limit" to PAGE_SIZE,
            "page" to page,
            "content_rating" to if (mature()) listOf("safe", "suggestive", "erotica") else listOf("safe", "suggestive"),
        )
        // Relevance is the API default for keyword searches; an explicit order could be rejected.
        when (order) {
            MangaOrder.LATEST -> params["order"] = mapOf("chapter_updated_at" to "desc")
            MangaOrder.POPULAR -> params["order"] = mapOf("views_total" to "desc")
            MangaOrder.RELEVANCE -> Unit
        }
        query.trim().takeIf { it.isNotEmpty() }?.let { params["keyword"] = it }
        type?.let { params["types"] = listOf(it.slug) }
        val result = runtime.call("browse", "", params)
        val map = result.map()
        val items = map["I7"].list().ifEmpty { map["EY"].list() }
            .map(::comixManga).filter { it.id != CX_PREFIX && it.title.isNotBlank() }
        val meta = map["aY"].map().ifEmpty { map["\$U"].map() }
        val total = meta.firstInt("T8", "fc") ?: items.size
        return MangaPage(items, total, (page - 1) * PAGE_SIZE)
    }

    suspend fun search(query: String): List<Manga> = browse(MangaOrder.RELEVANCE, query).items

    suspend fun detail(id: String): Manga {
        val hid = id.removePrefix(CX_PREFIX).substringBefore(':')
        return comixManga(runtime.call("detail", hid, null))
    }

    suspend fun chapters(id: String): List<MangaChapter> {
        val hid = id.removePrefix(CX_PREFIX).substringBefore(':')
        val all = ArrayList<MangaChapter>()
        var page = 1
        var pages = 1
        do {
            val result = runtime.call(
                "chapters",
                hid,
                mapOf("limit" to CHAPTER_LIMIT, "page" to page, "order" to mapOf("number" to "desc")),
            ).map()
            val items = result["I7"].list().ifEmpty { result["EY"].list() }
            all += items.map { comixChapter(it, hid) }.filter { it.language == "en" }
            val meta = result["aY"].map().ifEmpty { result["\$U"].map() }
            pages = meta.firstInt("yJ", "Gc") ?: page
            page++
        } while (page <= pages)
        return pickChapters(all, "en")
    }

    suspend fun pages(chapterId: String): List<String> {
        val (hid, id) = comixParts(chapterId) ?: return emptyList()
        return comixPageUrls(runtime.call("reader", hid, id))
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

    private companion object {
        const val PAGE_SIZE = 28
        const val CHAPTER_LIMIT = 100
    }
}

private class ComixWebRuntime(private val app: Application) {
    private val moshi = Moshi.Builder().build()
    private val jsonAdapter = moshi.adapter(Any::class.java)
    private val mutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, kotlin.coroutines.Continuation<Any?>>()
    private var webView: WebView? = null

    suspend fun call(operation: String, hid: String, argument: Any?): Any? = mutex.withLock {
        try {
            withTimeout(REQUEST_TIMEOUT_MS) { invoke(operation, hid, argument) }
        } catch (e: TimeoutCancellationException) {
            // A caller's own timeout (e.g. a bounded search) is cancellation, not a Comix failure.
            if (!currentCoroutineContext().isActive) throw e
            throw IOException("Comix request timed out", e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun invoke(operation: String, hid: String, argument: Any?): Any? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val token = UUID.randomUUID().toString()
            pending[token] = continuation
            continuation.invokeOnCancellation { pending.remove(token) }
            val view = webView ?: WebView(app).also { created ->
                created.settings.javaScriptEnabled = true
                created.settings.domStorageEnabled = true
                created.settings.userAgentString = USER_AGENT
                created.addJavascriptInterface(Bridge(), BRIDGE_NAME)
                webView = created
            }
            val target = "$COMIX_ORIGIN/browse"
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!url.startsWith(COMIX_ORIGIN)) return
                    view.evaluateJavascript(script(token, operation, hid, argument), null)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail(token, IOException("Comix WebView ${error.errorCode}: ${error.description}"))
                }
            }
            view.loadUrl(target)
        }
    }

    private fun script(token: String, operation: String, hid: String, argument: Any?): String {
        val arg = jsonAdapter.toJson(argument)
        return """
            (async () => {
              const token = ${jsString(token)};
              try {
                const main = [...document.scripts].map(s => s.src).find(s => /\/main-[^/]+\.js(?:\?|$)/.test(s));
                if (!main) throw new Error('Comix main module not found');
                const source = await (await fetch(main)).text();
                const match = source.match(/apicore-[A-Za-z0-9_-]+\.js/);
                if (!match) throw new Error('Comix API module not found');
                const api = await import(new URL(match[0], main).href);
                const groups = Object.values(api).filter(v => v && typeof v === 'object');
                const method = (group, names) => {
                  for (const name of names) {
                    try { if (typeof group[name] === 'function') return group[name].bind(group); } catch (_) {}
                  }
                  return null;
                };
                // The site's minified export letters change on deploy; find modules by the methods they expose.
                const find = (markers) => {
                  for (const group of groups) {
                    try { if (markers.every(([name, kind]) => typeof group[name] === kind)) return group; } catch (_) {}
                  }
                  return null;
                };
                const catalogue = find([['iL', 'function'], ['vS', 'function'], ['Wu', 'function'], ['Yx', 'function']])
                  ?? find([['CF', 'function'], ['uo', 'function'], ['Xr', 'function'], ['aM', 'function']]);
                if (!catalogue) throw new Error('Comix catalogue module not found');
                const reader = find([['vS', 'function'], ['qv', 'function']])
                  ?? find([['uo', 'function'], ['qv', 'function']]);
                if (!reader) throw new Error('Comix reader module not found');
                const op = ${jsString(operation)};
                const hid = ${jsString(hid)};
                const arg = $arg;
                let result;
                if (op === 'browse') {
                  const fn = method(catalogue, ['iL', 'CF']);
                  if (!fn) throw new Error('Comix browse unavailable');
                  result = await fn(arg);
                } else if (op === 'detail') {
                  const fn = method(catalogue, ['vS', 'uo']);
                  if (!fn) throw new Error('Comix detail unavailable');
                  result = await fn(hid);
                } else if (op === 'chapters') {
                  const fn = method(catalogue, ['Wu', 'Xr']);
                  if (!fn) throw new Error('Comix chapters unavailable');
                  result = await fn(hid, arg);
                } else if (op === 'reader') {
                  const fn = method(reader, ['vS', 'uo']);
                  if (!fn) throw new Error('Comix reader unavailable');
                  result = await fn(arg);
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
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
