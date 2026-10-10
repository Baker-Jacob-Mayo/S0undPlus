package com.s0undplus.twitch.update

import com.s0undplus.twitch.TwitchApiException
import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

object Versions {
    /** `v1.10.0-beta.2` → ([1, 10, 0], true). Non-numeric/empty input parses as ([0], false). */
    private fun parse(version: String): Pair<List<Int>, Boolean> {
        val core = version.trim().removePrefix("v").removePrefix("V")
        val preRelease = core.contains('-')
        val numbers = core.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
        return (numbers.ifEmpty { listOf(0) }) to preRelease
    }

    /** Negative if [a] < [b], 0 if equal, positive if [a] > [b]. A pre-release sorts below its release. */
    fun compare(a: String, b: String): Int {
        val (an, aPre) = parse(a)
        val (bn, bPre) = parse(b)
        for (i in 0 until maxOf(an.size, bn.size)) {
            val c = an.getOrElse(i) { 0 }.compareTo(bn.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            aPre == bPre -> 0
            aPre -> -1
            else -> 1
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}

@Serializable
internal data class ReleaseDto(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
internal data class AssetDto(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)

/** Looks for a newer published APK in a GitHub repository's latest release. */
class UpdateChecker(
    private val http: OkHttpClient,
    private val repo: String,
    private val apiUrl: String = "https://api.github.com",
) {
    /** The latest non-draft, non-prerelease release that has an APK, or null if there is none / repo not set. */
    suspend fun latest(): UpdateInfo? {
        if (repo.isBlank() || !repo.contains('/')) return null
        val request = Request.Builder()
            .url("$apiUrl/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).await().use { response ->
            val text = response.body.string()
            if (response.code == 404) return null // no releases yet
            if (!response.isSuccessful) throw TwitchApiException(response.code, "Update check failed", text)
            val release = TwitchJson.decodeFromString(ReleaseDto.serializer(), text)
            if (release.draft || release.prerelease) return null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
            return UpdateInfo(
                version = release.tag.removePrefix("v"),
                tag = release.tag,
                notes = release.body.orEmpty(),
                apkName = apk.name,
                apkUrl = apk.url,
                sizeBytes = apk.size,
            )
        }
    }

    /** [latest], but only if it is newer than [currentVersion]. */
    suspend fun newerThan(currentVersion: String): UpdateInfo? =
        latest()?.takeIf { Versions.isNewer(it.version, currentVersion) }
}
