package com.fuwanime

import com.fuwanime.data.manga.FeedDto
import com.fuwanime.data.manga.latestPerManga
import com.fuwanime.data.manga.mangaRelease
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaReleaseMappingTest {
    private val moshi = Moshi.Builder().build()

    private fun feed(): FeedDto {
        val text = checkNotNull(javaClass.classLoader?.getResource("mangadex/chapter_list.json")).readText()
        return checkNotNull(moshi.adapter(FeedDto::class.java).fromJson(text))
    }

    @Test fun readsIncludedMangaAndDropsNonOngoing() {
        val releases = feed().data.mapNotNull { it.mangaRelease() }
        assertEquals(2, releases.size)
        assertEquals("m1", releases[0].mangaId)
        assertEquals("Chainsaw Man", releases[0].title)
        assertEquals("6", releases[0].chapter)
        assertEquals("5", releases[1].chapter)
    }

    @Test fun latestPerMangaKeepsFirstOccurrence() {
        val deduped = latestPerManga(feed().data.mapNotNull { it.mangaRelease() })
        assertEquals(1, deduped.size)
        assertEquals("m1", deduped[0].mangaId)
        assertEquals("6", deduped[0].chapter)
    }
}
