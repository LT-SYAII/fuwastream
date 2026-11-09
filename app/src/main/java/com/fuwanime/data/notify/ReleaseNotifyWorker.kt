package com.fuwanime.data.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fuwanime.FuwaApp
import com.fuwanime.R
import com.fuwanime.util.localizedContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodically posts notifications for new episodes of any **ongoing** anime and new chapters of any
 * ongoing manga, not only the user's favourites. The first run per title only records a baseline, so
 * turning the feature on never greets the user with a burst of stale "new" pings.
 *
 * Favourited, ongoing anime are skipped here: [EpisodeNotifyWorker] already notifies them and writes
 * the Library badge baselines, so handling them in both places would double-notify.
 */
class ReleaseNotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FuwaApp).container
        val settings = container.settingsStore.settings.first()
        if (!settings.notifyOngoingAnime && !settings.notifyNewChapters) return Result.success()

        if (settings.notifyOngoingAnime) runCatching { notifyAnimeReleases() }
        if (settings.notifyNewChapters) runCatching { notifyMangaReleases() }
        return Result.success()
    }

    private suspend fun notifyAnimeReleases() {
        val container = (applicationContext as FuwaApp).container
        val favorites = container.libraryRepository.allFavorites().mapTo(HashSet()) { it.animeId }
        val releases = container.animeRepository.latestReleases(limit = ANIME_FEED).getOrNull().orEmpty()
        val res = localizedContext(applicationContext).resources
        val notifier = EpisodeNotifier(applicationContext)
        val notified = ArrayList<String>()

        for (release in releases) {
            val anime = release.anime
            if (anime.id in favorites) continue
            // The feed implies "recently aired"; only drop it when the API explicitly says it is finished.
            if (anime.status.isNotBlank() && !anime.status.equals("ONGOING", true)) continue
            val number = episodeNumber(release.episodeLabel) ?: continue

            val baseline = container.settingsStore.releaseBaseline(anime.id)
            container.settingsStore.setReleaseBaseline(anime.id, number)
            if (isNewerRelease(number, baseline)) {
                notifier.notifyAnime(
                    animeId = anime.id,
                    title = anime.title,
                    poster = anime.poster,
                    cover = anime.cover,
                    text = res.getString(R.string.system_new_episode_text, episodeLabelText(number)),
                )
                notified += anime.title
            }
        }
        if (notified.size >= 2) notifier.postSummary(notified)
    }

    private suspend fun notifyMangaReleases() {
        val container = (applicationContext as FuwaApp).container
        val releases = container.mangaRepository.latestOngoingChapters(limit = MANGA_FEED)
        val notifier = MangaNotifier(applicationContext)
        val notified = ArrayList<String>()

        for (release in releases) {
            val label = release.chapter ?: continue
            val number = label.toDoubleOrNull() ?: continue
            val baseline = container.settingsStore.mangaBaseline(release.mangaId)
            container.settingsStore.setMangaBaseline(release.mangaId, number)
            if (isNewerRelease(number, baseline)) {
                notifier.notifyNewChapter(release.mangaId, release.title, label)
                notified += release.title
            }
        }
        if (notified.size >= 2) notifier.postSummary(notified)
    }

    companion object {
        private const val WORK_NAME = "release_check"

        /** How many entries of each feed to scan per run; enough to cover a day without hammering the APIs. */
        private const val ANIME_FEED = 30
        private const val MANGA_FEED = 40

        /** Schedules the hourly sweep; safe to call on every app start (KEEP policy). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReleaseNotifyWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
