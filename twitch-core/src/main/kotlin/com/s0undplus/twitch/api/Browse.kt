package com.s0undplus.twitch.api

import com.s0undplus.twitch.Category
import com.s0undplus.twitch.Page
import com.s0undplus.twitch.Stream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The lists the Live, Categories and category pages show: from Twitch's directory ([DirectoryApi]), which can sort
 * and filter them every way the website does, or from Helix when the directory refuses and Helix can answer the same
 * question (most viewers first, by language; categories unfiltered).
 *
 * A list keeps to the source its first page came from: each cursor says which one it belongs to, since the two can't
 * continue each other's pages.
 */
class Browse(
    private val directoryStreams: suspend (filter: StreamFilter, gameId: String?, after: String?) -> Page<Stream>,
    private val helixStreams: suspend (languages: Set<String>, gameId: String?, after: String?) -> Page<Stream>,
    private val directoryCategories: suspend (tagIds: Set<String>, after: String?) -> Page<Category>,
    private val helixCategories: suspend (after: String?) -> Page<Category>,
    private val onFallback: (Throwable) -> Unit = {},
) {
    constructor(directory: DirectoryApi, helix: HelixApi, onFallback: (Throwable) -> Unit = {}) : this(
        directoryStreams = { f, game, after -> directory.streams(f, game, after) },
        helixStreams = { languages, game, after -> helix.streams(gameId = game, after = after, languages = languages) },
        directoryCategories = { tags, after -> directory.categories(tags, after) },
        helixCategories = { after -> helix.topCategories(after = after) },
        onFallback = onFallback,
    )

    suspend fun streams(filter: StreamFilter, gameId: String? = null, cursor: String? = null): Page<Stream> = when {
        cursor?.startsWith(HELIX) == true -> helixStreams(filter.languages, gameId, cursor.removePrefix(HELIX)).tagged(HELIX)
        cursor != null -> directoryStreams(filter, gameId, cursor.removePrefix(DIRECTORY)).tagged(DIRECTORY)
        else -> firstPage(filter.helixCan, { directoryStreams(filter, gameId, null) }, { helixStreams(filter.languages, gameId, null) })
    }

    suspend fun categories(tagIds: Set<String>, cursor: String? = null): Page<Category> = when {
        cursor?.startsWith(HELIX) == true -> helixCategories(cursor.removePrefix(HELIX)).tagged(HELIX)
        cursor != null -> directoryCategories(tagIds, cursor.removePrefix(DIRECTORY)).tagged(DIRECTORY)
        else -> firstPage(tagIds.isEmpty(), { directoryCategories(tagIds, null) }, { helixCategories(null) })
    }

    private suspend fun <T> firstPage(helixCan: Boolean, directory: suspend () -> Page<T>, helix: suspend () -> Page<T>): Page<T> =
        try {
            directory().tagged(DIRECTORY)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!helixCan) throw e
            onFallback(e)
            helix().tagged(HELIX)
        }

    private fun <T> Page<T>.tagged(prefix: String): Page<T> = copy(nextCursor = nextCursor?.let { prefix + it })

    private companion object {
        const val DIRECTORY = "d:"
        const val HELIX = "h:"
    }
}
