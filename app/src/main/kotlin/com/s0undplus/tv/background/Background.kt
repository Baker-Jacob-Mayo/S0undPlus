package com.s0undplus.tv.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.s0undplus.tv.R
import com.s0undplus.tv.S0undPlusApp
import com.s0undplus.tv.data.AppSettings
import com.s0undplus.twitch.Stream
import com.s0undplus.twitch.TwitchApiException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Runs about every 15 minutes (the minimum WorkManager allows): finds followed channels that have gone live since the
 * last check and tells the viewer, and refreshes the Android TV home-screen row.
 */
class LiveCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as S0undPlusApp).container
        val settings = container.settings.settings.first()
        if (!settings.goLiveNotifications && !settings.homeChannel) return Result.success()

        container.session.restore()
        if (container.session.tokens.value == null) return Result.success() // signed out: nothing to do, quietly

        val live = try {
            container.helix.followedStreams(first = 100).items
        } catch (e: TwitchApiException) {
            // 401/403: the login expired or lost its scope. Retrying won't help; the app will ask to sign in again.
            return if (e.status == 401 || e.status == 403) Result.success() else Result.retry()
        } catch (_: IOException) {
            return Result.retry()
        }

        val nowLive = live.map { it.userLogin.lowercase() }.toSet()
        val previous = container.settings.lastLiveLogins()
        // First ever run: remember who is live without announcing all of them at once.
        if (settings.goLiveNotifications && previous != null) {
            Notifier.notifyWentLive(applicationContext, live.filter { it.userLogin.lowercase() !in previous })
        }
        container.settings.saveLastLiveLogins(nowLive)
        if (settings.homeChannel) TvChannels.publish(applicationContext, live)
        return Result.success()
    }
}

object LiveCheckScheduler {
    private const val PERIODIC = "live-check"
    private const val NOW = "live-check-now"

    /** Starts or stops the periodic check to match [settings]; [runNow] also does one check immediately. */
    fun sync(context: Context, settings: AppSettings, runNow: Boolean = false) {
        val work = WorkManager.getInstance(context)
        if (!settings.goLiveNotifications && !settings.homeChannel) {
            work.cancelUniqueWork(PERIODIC)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LiveCheckWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
        if (runNow) {
            work.enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<LiveCheckWorker>().setConstraints(constraints).build(),
            )
        }
    }
}

object Notifier {
    private const val CHANNEL_ID = "go_live"
    private const val MAX_PER_CHECK = 5

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifyWentLive(context: Context, streams: List<Stream>) {
        if (streams.isEmpty() || !hasPermission(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Followed channels going live", NotificationManager.IMPORTANCE_DEFAULT),
        )
        for (stream in streams.take(MAX_PER_CHECK)) {
            val open = Intent(Intent.ACTION_VIEW, Uri.parse("s0undplus://live/${stream.userLogin}"))
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                context,
                stream.userLogin.hashCode(),
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${stream.userName} is live")
                .setContentText(stream.title.ifBlank { stream.gameName })
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .build()
            manager.notify(stream.userLogin.hashCode(), notification)
        }
    }
}

/**
 * A "Live now" preview row on the Android TV launcher, via the TV provider. Launchers that don't implement app
 * channels (Google TV's newer home screen may not) simply never show it; every call here fails soft.
 */
object TvChannels {
    private const val PREFS = "tv_channel"
    private const val KEY_ID = "channel_id"
    private const val MAX_PROGRAMS = 20

    /** Creates the channel if needed (quietly). */
    fun ensure(context: Context) {
        ensureChannel(context)
    }

    /** Creates the channel and asks the viewer, once, to let it appear on the home screen. */
    fun requestBrowsable(activity: Activity) {
        val id = ensureChannel(activity) ?: return
        try {
            TvContractCompat.requestChannelBrowsable(activity, id)
        } catch (_: Exception) {
            // no TV provider / dialog unavailable
        }
    }

    // Lint reports RestrictedApi for PreviewProgram.Builder's generic setters. It is a false positive: this exact
    // builder chain is the one documented for the TV provider, and the methods are public API.
    @SuppressLint("RestrictedApi")
    fun publish(context: Context, streams: List<Stream>) {
        val id = ensureChannel(context) ?: return
        try {
            context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(id), null, null)
            for (stream in streams.take(MAX_PROGRAMS)) {
                val program = PreviewProgram.Builder()
                    .setChannelId(id)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                    .setTitle(stream.userName)
                    .setDescription(stream.title.ifBlank { stream.gameName })
                    .setPosterArtUri(Uri.parse(stream.thumbnail(640, 360)))
                    .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                    .setIntentUri(Uri.parse("s0undplus://live/${stream.userLogin}"))
                    .setInternalProviderId(stream.userLogin)
                    .build()
                context.contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program.toContentValues())
            }
        } catch (_: Exception) {
            // launcher without TV provider support
        }
    }

    fun remove(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_ID, -1)
        if (id < 0) return
        try {
            context.contentResolver.delete(TvContractCompat.buildChannelUri(id), null, null)
        } catch (_: Exception) {
            // already gone
        }
        prefs.edit().remove(KEY_ID).apply()
    }

    private fun ensureChannel(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getLong(KEY_ID, -1)
        if (saved >= 0 && exists(context, saved)) return saved
        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName("Live now on S0undPlus")
            .setAppLinkIntentUri(Uri.parse("s0undplus://home"))
            .build()
        val uri = try {
            context.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, channel.toContentValues())
        } catch (_: Exception) {
            null
        } ?: return null
        val id = ContentUris.parseId(uri)
        prefs.edit().putLong(KEY_ID, id).apply()
        return id
    }

    private fun exists(context: Context, id: Long): Boolean = try {
        context.contentResolver.query(TvContractCompat.buildChannelUri(id), arrayOf(TvContractCompat.Channels._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    } catch (_: Exception) {
        false
    }
}
