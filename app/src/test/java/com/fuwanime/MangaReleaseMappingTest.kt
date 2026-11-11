package com.fuwanime

import com.fuwanime.data.manga.ChapterAttributesDto
import com.fuwanime.data.manga.ChapterDto
import com.fuwanime.data.manga.FeedDto
import com.fuwanime.data.manga.RelationshipAttributesDto
import com.fuwanime.data.manga.RelationshipDto
import com.fuwanime.data.manga.latestPerManga
import com.fuwanime.data.manga.mangaRelease
import com.squareup.moshi.Moshi
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MangaReleaseMappingTest {
    private val moshi = Moshi.Builder().build()

    /** After every date in the fixture, so the test does not depend on the wall clock. */
    private val now = Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()

    private fun feed(): FeedDto {
        val text = checkNotNull(javaClass.classLoader?.getResource("mangadex/chapter_list.json")).readText()
        return checkNotNull(moshi.adapter(FeedDto::class.java).fromJson(text))
    }

    private fun chapter(
        id: String,
        number: String?,
        publishAt: String?,
        readableAt: String? = publishAt,
        mangaId: String = "m1",
        status: String = "ongoing",
    ) = ChapterDto(
        id = id,
        attributes = ChapterAttributesDto(chapter = number, publishAt = publishAt, readableAt = readableAt),
        relationships = listOf(
            RelationshipDto(
                id = mangaId,
                type = "manga",
                attributes = RelationshipAttributesDto(title = mapOf("en" to "Chainsaw Man"), status = status),
            ),
        ),
    )

    @Test fun readsIncludedMangaAndDropsNonOngoing() {
        val releases = feed().data.mapNotNull { it.mangaRelease(now) }
        assertEquals(2, releases.size)
        assertEquals("m1", releases[0].mangaId)
        assertEquals("Chainsaw Man", releases[0].title)
        assertEquals("6", releases[0].chapter)
        assertEquals("5", releases[1].chapter)
    }

    @Test fun latestPerMangaKeepsFirstOccurrence() {
        val deduped = latestPerManga(feed().data.mapNotNull { it.mangaRelease(now) })
        assertEquals(1, deduped.size)
        assertEquals("m1", deduped[0].mangaId)
        assertEquals("6", deduped[0].chapter)
    }

    @Test fun dropsChapterThatIsNotReadableYet() {
        val release = chapter(
            id = "future",
            number = "8",
            publishAt = "2037-12-31T15:00:00+00:00",
            readableAt = "2037-12-31T15:00:00+00:00",
        ).mangaRelease(now)
        assertNull(release)
    }

    @Test fun readableAtWinsOverFarFuturePublishAt() {
        val publishAt = "2037-12-31T15:00:00+00:00"
        val readableAt = "2026-11-08T12:00:00+00:00"
        val release = chapter(id = "old-upload", number = "6", publishAt = publishAt, readableAt = readableAt)
            .mangaRelease(now)
        assertNotNull(release)
        assertEquals(Instant.parse(readableAt).toEpochMilli(), release!!.publishAt)
    }

    @Test fun missingReadableAtFallsBackToPublishAt() {
        val release = chapter(
            id = "legacy",
            number = "7",
            publishAt = "2026-11-08T13:00:00+00:00",
            readableAt = null,
        ).mangaRelease(now)
        assertNotNull(release)
        assertEquals(Instant.parse("2026-11-08T13:00:00+00:00").toEpochMilli(), release!!.publishAt)
    }
}
