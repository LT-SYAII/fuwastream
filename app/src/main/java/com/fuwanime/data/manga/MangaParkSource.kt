package com.fuwanime.data.manga

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// MangaPark English catalogue (best-effort; site structure varies by domain/version).
const val MP_PREFIX = "mp:"

fun isMangaParkId(id: String): Boolean = id.startsWith(MP_PREFIX)

private fun String.mpRaw() = removePrefix(MP_PREFIX)

class MangaParkSource(private val client: OkHttpClient) {
    private val mirrors = listOf(
        "https://mangapark.cc",
        "https://mangapark.net",
        "https://mangapark.io",
        "https://mangapark.to",
        "https://mangapark.com",
        "https://mangapark.org",
        "https://mangapark.me",
    )

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://mangapark.net/")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null else res.body?.string()
            }
        }.getOrNull()?.takeIf { it.length > 800 && "cf-browser-verification" !in it }
    }

    suspend fun search(query: String): List<Manga> {
        val enc = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val paths = listOf("/search?word=$enc", "/search?q=$enc")
        for (base in mirrors) {
            for (p in paths) {
                val html = get("$base$p") ?: continue
                val items = parseList(html, base)
                if (items.isNotEmpty()) return items
            }
        }
        return emptyList()
    }

    suspend fun find(names: List<String>): Manga? {
        for (name in names.filter { it.isNotBlank() }.distinct().take(4)) {
            val hit = runCatching { search(name) }.getOrNull()
                ?.firstOrNull { sameTitle(name, it.title, it.altTitles) }
            if (hit != null) return hit
        }
        return null
    }

    suspend fun detail(id: String): Manga {
        val slug = id.mpRaw().substringBefore('|')
        val hintBase = id.mpRaw().substringAfter('|', "")
        val bases = (if (hintBase.isNotBlank()) listOf(hintBase) else emptyList()) + mirrors
        for (base in bases.distinct()) {
            val html = get("$base/title/$slug") ?: get("$base/comic/$slug") ?: continue
            val m = parseDetail(html, slug, base)
            if (m.title.isNotBlank()) return m
        }
        throw IllegalStateException("Manga not found")
    }

    suspend fun chapters(id: String): List<MangaChapter> {
        val slug = id.mpRaw().substringBefore('|')
        val hintBase = id.mpRaw().substringAfter('|', "")
        val bases = (if (hintBase.isNotBlank()) listOf(hintBase) else emptyList()) + mirrors
        for (base in bases.distinct()) {
            val html = get("$base/title/$slug") ?: get("$base/comic/$slug") ?: continue
            val ch = parseChapters(html, slug, base)
            if (ch.isNotEmpty()) return pickChapters(ch, "en")
        }
        return emptyList()
    }

    suspend fun pages(chapterId: String): List<String> {
        val raw = chapterId.mpRaw()
        val parts = raw.split('|', limit = 3)
        val hintBase = parts.getOrNull(1).orEmpty()
        val chap = parts.getOrNull(2).orEmpty()
        val candidates = mutableListOf<String>()
        if (chap.startsWith("http")) candidates += chap
        else {
            val bases = (if (hintBase.isNotBlank()) listOf(hintBase) else emptyList()) + mirrors
            for (base in bases.distinct()) candidates += if (chap.startsWith("/")) "$base$chap" else "$base/$chap"
        }
        for (url in candidates.distinct()) {
            val html = get(url) ?: continue
            val imgs = parsePages(html)
            if (imgs.isNotEmpty()) return imgs
        }
        return emptyList()
    }

    internal fun parseList(html: String, base: String): List<Manga> {
        val out = LinkedHashMap<String, Manga>()
        val linkRe = Regex("""href="([^"]*/(?:title|comic)/([^"/?#]+)[^"]*)"""")
        val imgRe = Regex("""<img[^>]+(?:src|data-src)="([^"]+)"""")
        val titleAttr = Regex("""title="([^"]+)"""")
        val blockRe = Regex("""(?s)<(?:div|a)[^>]*>.*?href="[^"]*/(?:title|comic)/[^"]+".*?</(?:div|a)>""")
        for (b in blockRe.findAll(html)) {
            val block = b.value
            val lm = linkRe.find(block) ?: continue
            val slug = lm.groupValues[2]
            val title = titleAttr.find(block)?.groupValues?.getOrNull(1)?.let(::htmlText)?.trim()
                ?: Regex("""<h3[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.getOrNull(1)?.let(::htmlText)?.trim()
                ?: continue
            val img = imgRe.find(block)?.groupValues?.getOrNull(1)?.let { absUrl(it, base) }
            val id = "$MP_PREFIX$slug|$base"
            if (id !in out) out[id] = Manga(id, title, img, "", emptyList(), MangaStatus.UNKNOWN, null, null, type = "Manga")
        }
        return out.values.toList()
    }

    internal fun parseDetail(html: String, slug: String, base: String): Manga {
        val title = Regex("""<h3[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.getOrNull(1)?.let(::htmlText)?.trim()
            ?: Regex("""property="og:title"\s+content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)?.substringBefore(" -")?.trim()
            ?: slug.replace('-', ' ')
        val cover = Regex("""property="og:image"\s+content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
        val desc = Regex("""property="og:description"\s+content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)?.let(::htmlText).orEmpty()
        val status = when {
            Regex("""status[^<]*completed""", RegexOption.IGNORE_CASE).containsMatchIn(html) -> MangaStatus.COMPLETED
            Regex("""status[^<]*ongoing|status[^<]*releasing""", RegexOption.IGNORE_CASE).containsMatchIn(html) -> MangaStatus.ONGOING
            Regex("""status[^<]*hiatus""", RegexOption.IGNORE_CASE).containsMatchIn(html) -> MangaStatus.HIATUS
            else -> MangaStatus.UNKNOWN
        }
        return Manga("$MP_PREFIX$slug|$base", title, cover?.let { absUrl(it, base) }, desc, emptyList(), status, null, null, type = "Manga")
    }

    internal fun parseChapters(html: String, slug: String, base: String): List<MangaChapter> {
        val out = mutableListOf<MangaChapter>()
        val linkRe = Regex("""href="(/(?:title|comic)/[^"]+/[^"]*chapter[^"]*|https?://[^"]+/chapter[^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val numRe = Regex("""(?:chapter|ch\.?|episode|ep\.?)[\s._-]*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
        for (m in linkRe.findAll(html)) {
            val href = m.groupValues[1]
            val label = htmlText(m.groupValues[2]).trim()
            val number = numRe.find(label)?.groupValues?.getOrNull(1) ?: numRe.find(href)?.groupValues?.getOrNull(1)
            out += MangaChapter(
                id = "$MP_PREFIX$slug|$base|$href",
                number = number,
                title = label.takeIf { it.isNotBlank() },
                language = "en",
                group = "MangaPark",
                pages = 1,
                externalUrl = null,
                publishAt = 0L,
            )
        }
        // de-dup by chapter id/number keeping first
        return out.distinctBy { it.id }
    }

    internal fun parsePages(html: String): List<String> {
        // try JSON array in scripts (common in newer mangapark)
        val jsonImgs = Regex("""https?://[^"'\s\\]+\.(?:jpg|jpeg|png|webp)[^"'\s\\]*""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.value.replace("\\/", "/") }
            .filter { "/comic/" in it || "/media/" in it || "/chapter" in it || "mp" in it || "mangapark" in it }
            .distinct().toList()
        if (jsonImgs.size >= 2) return jsonImgs
        val imgRe = Regex("""<img[^>]+(?:data-src|src)="([^"]+)"""")
        return imgRe.findAll(html).map { it.groupValues[1] }
            .filter { Regex("""\.(jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .filterNot { "logo" in it || "avatar" in it || "icon" in it || "ads" in it }
            .distinct().toList()
    }

    private fun absUrl(url: String, base: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        url.startsWith("/") -> base.trimEnd('/') + url
        else -> base.trimEnd('/') + "/" + url
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
