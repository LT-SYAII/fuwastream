package com.fuwanime.data.manga

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

// KakaManga / MangaKakalot-family English catalogue. Best-effort scraper with mirror fallbacks.
const val KK_PREFIX = "kk:"

fun isKakaMangaId(id: String): Boolean = id.startsWith(KK_PREFIX)

private fun String.kkRaw() = removePrefix(KK_PREFIX)

class KakaMangaSource(private val client: OkHttpClient) {
    private val mirrors = listOf(
        "https://www.kakamanga.com",
        "https://www.natomanga.com",
        "https://www.mangakakalot.gg",
        "https://www.nelomanga.com",
        "https://www.manganato.gg",
    )

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", mirrors.first())
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null else res.body?.string()
            }
        }.getOrNull()?.takeIf { "Just a moment" !in it && it.length > 500 }
    }

    private suspend fun getAny(pathBuilder: (String) -> String): Pair<String, String>? {
        for (base in mirrors) {
            val html = get(pathBuilder(base))
            if (html != null) return base to html
        }
        return null
    }

    suspend fun search(query: String): List<Manga> {
        val q = query.trim().replace(Regex("\\s+"), "_")
        val enc = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val candidates = listOf<(String) -> String>(
            { "$it/search/story/$q" },
            { "$it/search/story/${q.lowercase()}" },
            { "$it/search/story/${enc.replace("+", "_")}" },
            { "$it/?s=$enc" },
        )
        for (build in candidates) {
            for (base in mirrors) {
                val html = get(build(base)) ?: continue
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
        val slug = id.kkRaw().substringBefore('|')
        val hintBase = id.kkRaw().substringAfter('|', "")
        val bases = (if (hintBase.isNotBlank()) listOf(hintBase) else emptyList()) + mirrors
        for (base in bases.distinct()) {
            val html = get("$base/manga/$slug") ?: continue
            val m = parseDetail(html, slug, base)
            if (m.title.isNotBlank()) return m
        }
        throw IllegalStateException("Manga not found")
    }

    suspend fun chapters(id: String): List<MangaChapter> {
        val slug = id.kkRaw().substringBefore('|')
        val hintBase = id.kkRaw().substringAfter('|', "")
        val bases = (if (hintBase.isNotBlank()) listOf(hintBase) else emptyList()) + mirrors
        for (base in bases.distinct()) {
            val html = get("$base/manga/$slug") ?: continue
            val ch = parseChapters(html, slug, base)
            if (ch.isNotEmpty()) return pickChapters(ch, "en")
        }
        return emptyList()
    }

    suspend fun pages(chapterId: String): List<String> {
        // chapterId: kk:<mangaSlug>|<base>|<chapterPathOrUrl>
        val raw = chapterId.kkRaw()
        val parts = raw.split('|', limit = 3)
        val slug = parts.getOrNull(0).orEmpty()
        val hintBase = parts.getOrNull(1).orEmpty()
        val chap = parts.getOrNull(2).orEmpty()
        val candidates = mutableListOf<String>()
        if (chap.startsWith("http")) candidates += chap
        else {
            val bases = (if (hintBase.isNotBlank()) listOf(hintBase) else emptyList()) + mirrors
            for (base in bases.distinct()) {
                if (chap.startsWith("/")) candidates += "$base$chap" else candidates += "$base/manga/$slug/$chap"
            }
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
        // story_item / search-story-item / list-truyen-item patterns
        val itemRe = Regex("""(?s)<(?:div|li)[^>]*class="[^"]*(?:story_item|search-story-item|list-truyen-item|item)[^"]*"[^>]*>.*?</(?:div|li)>""")
        val linkRe = Regex("""href="([^"]*/manga/([^"/?#]+)[^"]*)"""")
        val imgRe = Regex("""<img[^>]+(?:src|data-src)="([^"]+)"""")
        val titleRe = Regex("""title="([^"]+)"""")
        val h3Re = Regex("""<h3[^>]*>.*?<a[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        for (m in itemRe.findAll(html)) {
            val block = m.value
            val lm = linkRe.find(block) ?: continue
            val slug = lm.groupValues[2]
            val img = imgRe.find(block)?.groupValues?.getOrNull(1)?.let { absUrl(it, base) }
            val title = titleRe.find(lm.value)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: h3Re.find(block)?.groupValues?.getOrNull(1)?.let(::htmlText)
                ?: continue
            val id = "$KK_PREFIX$slug|$base"
            if (id !in out) out[id] = Manga(id, title, img, "", emptyList(), MangaStatus.UNKNOWN, null, null, type = "Manga")
        }
        // fallback: generic manga links
        if (out.isEmpty()) {
            val generic = Regex("""<a[^>]+href="([^"]*/manga/([^"/?#]+))"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            for (m in generic.findAll(html)) {
                val slug = m.groupValues[2]
                if (slug in setOf("list", "search", "genre", "latest", "hot")) continue
                val title = htmlText(m.groupValues[3]).trim().ifBlank { null } ?: continue
                val id = "$KK_PREFIX$slug|$base"
                if (id !in out) out[id] = Manga(id, title, null, "", emptyList(), MangaStatus.UNKNOWN, null, null, type = "Manga")
            }
        }
        return out.values.toList()
    }

    internal fun parseDetail(html: String, slug: String, base: String): Manga {
        val title = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.getOrNull(1)?.let(::htmlText)?.trim()
            ?: Regex("""property="og:title"\s+content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)?.substringBefore(" |")?.substringBefore(" -")?.trim()
            ?: slug.replace('-', ' ')
        val cover = Regex("""<span[^>]*class="[^"]*info-image[^"]*"[^>]*>.*?<img[^>]+(?:src|data-src)="([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""<div[^>]*class="[^"]*manga-info-pic[^"]*"[^>]*>.*?<img[^>]+(?:src|data-src)="([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""property="og:image"\s+content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
        val desc = Regex("""<div[^>]*id="(?:noidungm|panel-story-info-description|contentBox)"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.getOrNull(1)?.let(::htmlText)?.trim()
            ?: Regex("""property="og:description"\s+content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)?.let(::htmlText).orEmpty()
        val infoBlock = Regex("""<ul[^>]*class="[^"]*manga-info-text[^"]*"[^>]*>(.*?)</ul>""", RegexOption.DOT_MATCHES_ALL).find(html)?.value ?: html
        val statusText = Regex("""Status\s*:?\s*</[^>]+>\s*([^<]+)""", RegexOption.IGNORE_CASE).find(infoBlock)?.groupValues?.getOrNull(1)?.trim()
            ?: Regex("""Status\s*:?\s*([^<\n]+)""", RegexOption.IGNORE_CASE).find(infoBlock)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val status = when {
            statusText.contains("ongoing", true) -> MangaStatus.ONGOING
            statusText.contains("completed", true) -> MangaStatus.COMPLETED
            statusText.contains("hiatus", true) -> MangaStatus.HIATUS
            else -> MangaStatus.UNKNOWN
        }
        val authors = Regex("""Author[^<]*</[^>]+>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL).find(infoBlock)?.groupValues?.getOrNull(1)
            ?.let { Regex("""<a[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(it).map { m -> htmlText(m.groupValues[1]).trim() }.filter { it.isNotBlank() }.toList() }
            .orEmpty()
        val genres = Regex("""Genres?[^<]*</[^>]+>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL).find(infoBlock)?.groupValues?.getOrNull(1)
            ?.let { Regex("""<a[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(it).map { m -> htmlText(m.groupValues[1]).trim() }.filter { it.isNotBlank() }.toList() }
            .orEmpty()
        val alts = Regex("""Alternative[^<]*</[^>]+>\s*([^<]+)""", RegexOption.IGNORE_CASE).find(infoBlock)?.groupValues?.getOrNull(1)
            ?.split(';', ',', '/')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        return Manga(
            id = "$KK_PREFIX$slug|$base",
            title = title,
            coverUrl = cover?.let { absUrl(it, base) },
            description = desc,
            tags = genres,
            status = status,
            year = null,
            author = authors.joinToString().ifBlank { null },
            altTitles = alts,
            type = "Manga",
        )
    }

    internal fun parseChapters(html: String, slug: String, base: String): List<MangaChapter> {
        val out = mutableListOf<MangaChapter>()
        // common: div.chapter-list div.row  OR ul.row-content-chapter li
        val rowRe = Regex("""(?s)<(?:li|div)[^>]*class="[^"]*(?:a-h|row)[^"]*"[^>]*>.*?</(?:li|div)>""")
        val rows = rowRe.findAll(html).toList().ifEmpty {
            Regex("""(?s)<li[^>]*>.*?href="[^"]*(?:/chapter|/chap)[^"]*".*?</li>""").findAll(html).toList()
        }
        val linkRe = Regex("""href="([^"]+/(?:chapter|chap)[^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val timeRe = Regex("""<span[^>]*(?:class="[^"]*chapter-time[^"]*"|title="([^"]+)")[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
        val numRe = Regex("""(?:chapter|chap)[\s._-]*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
        for (row in rows) {
            val block = row.value
            val lm = linkRe.find(block) ?: continue
            val href = lm.groupValues[1]
            val chapPath = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> href
                else -> "/$href"
            }
            val chSlug = chapPath.substringAfterLast('/')
            val label = htmlText(lm.groupValues[2]).trim()
            val number = numRe.find(chSlug)?.groupValues?.getOrNull(1) ?: numRe.find(label)?.groupValues?.getOrNull(1)
            val timeRaw = timeRe.find(block)?.let { it.groupValues[1].ifBlank { htmlText(it.groupValues[2]) } }.orEmpty()
            val publishAt = parseRelativeOrDate(timeRaw)
            out += MangaChapter(
                id = "$KK_PREFIX$slug|$base|$chapPath",
                number = number,
                title = label.takeIf { it.isNotBlank() && !it.equals("Chapter $number", true) },
                language = "en",
                group = "KakaManga",
                pages = 1,
                externalUrl = null,
                publishAt = publishAt,
            )
        }
        return out
    }

    internal fun parsePages(html: String): List<String> {
        val container = Regex("""(?s)<div[^>]*class="[^"]*container-chapter-reader[^"]*"[^>]*>(.*?)</div>\s*(?:<div|<footer|$)""").find(html)?.groupValues?.getOrNull(1)
            ?: html
        val imgRe = Regex("""<img[^>]+(?:src|data-src)="([^"]+)"""")
        return imgRe.findAll(container).map { absUrl(it.groupValues[1], mirrors.first()) }
            .filter { u -> u.contains("2xstorage") || u.contains("img") || u.contains("chapter") || u.contains("/kk/") || Regex("""\.(jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE).containsMatchIn(u) }
            .filterNot { "logo" in it || "avatar" in it || "icon" in it }
            .distinct().toList()
    }

    private fun absUrl(url: String, base: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        url.startsWith("/") -> base.trimEnd('/') + url
        else -> base.trimEnd('/') + "/" + url
    }

    private fun parseRelativeOrDate(raw: String): Long {
        val s = raw.trim().lowercase()
        val now = System.currentTimeMillis()
        val rel = Regex("""(\d+)\s*(minute|hour|day|week|month|year)""").find(s)
        if (rel != null) {
            val n = rel.groupValues[1].toLongOrNull() ?: 0L
            val ms = when (rel.groupValues[2]) {
                "minute" -> 60_000L
                "hour" -> 3_600_000L
                "day" -> 86_400_000L
                "week" -> 7L * 86_400_000L
                "month" -> 30L * 86_400_000L
                "year" -> 365L * 86_400_000L
                else -> 0L
            }
            return now - n * ms
        }
        val fmts = listOf("MMM dd,yyyy HH:mm", "MMM dd,yyyy", "MMM dd, yy", "dd/MM/yyyy", "yyyy-MM-dd")
        for (f in fmts) {
            try {
                val df = SimpleDateFormat(f, Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val t = df.parse(raw.trim())?.time
                if (t != null) return t
            } catch (_: Exception) {}
        }
        return 0L
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
