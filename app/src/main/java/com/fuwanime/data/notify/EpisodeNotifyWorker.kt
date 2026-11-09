package com.fuwanime.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import com.fuwanime.util.loadBitmap
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fuwanime.FuwaApp
import com.fuwanime.MainActivity
import com.fuwanime.R
import com.fuwanime.data.Episode
import com.fuwanime.data.local.FavoriteEntity
import com.fuwanime.util.localizedContext
import android.content.res.Resources
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodically checks favorited, ongoing anime for newly released episodes and posts a
 * notification. The first run only records a baseline per anime, so the user is never
 * greeted with a burst of "new episode" pings for content that already existed.
 */
class EpisodeNotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FuwaApp).container
        val settings = container.settingsStore
        if (!settings.settings.first().notifyNewEpisodes) return Result.success()

        val favorites = container.libraryRepository.allFavorites()
            .filter { it.status.equals("ONGOING", true) }
            .take(25)
        val notifier = EpisodeNotifier(applicationContext)
        val notified = mutableListOf<String>()
        for (fav in favorites) {
            runCatching {
                val newest = container.animeRepository
                    .episodePage(fav.animeId, 0).getOrNull()
                    ?.maxByOrNull { it.sortKey } ?: return@runCatching
                if (newest.sortKey == Double.MAX_VALUE) return@runCatching

                val baseline = settings.notifyBaseline(fav.animeId)
                settings.setNotifyBaseline(fav.animeId, newest.sortKey)
                if (baseline != null && newest.sortKey > baseline + 0.001) {
                    notifier.notifyNewEpisode(fav, newest)
                    notified += fav.title
                }
            }
        }
        if (notified.size >= 2) notifier.postSummary(notified)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "episode_check"

        /** Schedules the daily-ish sweep; safe to call on every app start (KEEP policy). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EpisodeNotifyWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/** Posts "new episode" notifications on a dedicated channel; silent no-op without permission. */
class EpisodeNotifier(private val context: Context) {

    private val res get() = localizedContext(context).resources

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** False without notification permission; otherwise makes sure the channel exists. */
    private fun ready(): Boolean = ensureChannel(
        context, manager, CHANNEL_ID,
        res.getString(R.string.system_channel_new_episodes_name),
        res.getString(R.string.system_channel_new_episodes_desc),
    )

    suspend fun notifyNewEpisode(favorite: FavoriteEntity, episode: Episode) = notifyAnime(
        animeId = favorite.animeId,
        title = favorite.title,
        poster = favorite.poster,
        cover = favorite.cover,
        text = res.getString(R.string.system_new_episode_text, episode.number),
    )

    /** Posts a "new episode" notification for any anime (favourites and the global release feed). */
    suspend fun notifyAnime(animeId: String, title: String, poster: String, cover: String, text: String) {
        if (!ready()) return

        val contentIntent = PendingIntent.getActivity(
            context,
            animeId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_ANIME_ID, animeId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Artwork is a bonus: when it can't load the notification stays text-only.
        val posterBitmap = loadBitmap(context, poster, 256)
        val coverBitmap = loadBitmap(context, cover.ifBlank { poster }, 1024)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setGroup(GROUP_KEY)
            .apply {
                if (posterBitmap != null) setLargeIcon(posterBitmap)
                if (coverBitmap != null) {
                    setStyle(NotificationCompat.BigPictureStyle().bigPicture(coverBitmap).bigLargeIcon(null as Bitmap?).setSummaryText(text))
                }
            }
            .build()
        manager.notify(animeId.hashCode(), notification)
    }

    /** Collapses several "new episode" notifications under one "N new episodes" header. */
    fun postSummary(titles: List<String>) {
        if (!ready()) return
        val style = NotificationCompat.InboxStyle().also { inbox -> titles.forEach { inbox.addLine(it) } }
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(groupSummaryTitle(res, titles.size))
            .setContentText(titles.joinToString(", "))
            .setStyle(style)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        manager.notify(SUMMARY_ID, summary)
    }

    companion object {
        const val CHANNEL_ID = "new_episodes"
        const val GROUP_KEY = "new_episodes"
        const val SUMMARY_ID = 0x5EED
    }
}

/** Title of the grouped "new episode" summary. */
fun groupSummaryTitle(res: Resources, count: Int): String =
    res.getQuantityString(R.plurals.system_new_episodes_summary, count, count)

/** Title of the grouped "new chapter" summary. */
fun chapterSummaryTitle(res: Resources, count: Int): String =
    res.getQuantityString(R.plurals.system_new_chapters_summary, count, count)

/**
 * False without the notification permission; otherwise makes sure a channel with [channelId] exists.
 * Shared by every notifier so the permission check and channel setup live in one place.
 */
fun ensureChannel(
    context: Context,
    manager: NotificationManager,
    channelId: String,
    name: String,
    description: String,
): Boolean {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) return false
    if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(
            NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_DEFAULT).apply { this.description = description },
        )
    }
    return true
}
