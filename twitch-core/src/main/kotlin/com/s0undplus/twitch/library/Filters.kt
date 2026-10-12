package com.s0undplus.twitch.library

import com.s0undplus.twitch.TwitchJson
import com.s0undplus.twitch.api.StreamFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/** The filter the viewer set on one category's page. */
@Serializable
data class CategoryFilter(val id: String, val filter: StreamFilter)

/** How the browse pages' filters are kept between launches. Corrupt or missing data reads as "not set". */
object FilterOps {
    /** Category pages whose filter is remembered; the ones changed longest ago are forgotten first. */
    const val MAX_CATEGORY_FILTERS = 50

    fun encodeFilter(filter: StreamFilter): String = TwitchJson.encodeToString(StreamFilter.serializer(), filter)

    fun decodeFilter(json: String?): StreamFilter? =
        json?.takeIf { it.isNotBlank() }?.let { runCatching { TwitchJson.decodeFromString(StreamFilter.serializer(), it) }.getOrNull() }

    fun encodeIds(ids: Set<String>): String = TwitchJson.encodeToString(SetSerializer(String.serializer()), ids)

    fun decodeIds(json: String?): Set<String> =
        json?.takeIf { it.isNotBlank() }?.let { runCatching { TwitchJson.decodeFromString(SetSerializer(String.serializer()), it) }.getOrNull() }
            .orEmpty()

    fun encodeCategoryFilters(list: List<CategoryFilter>): String =
        TwitchJson.encodeToString(ListSerializer(CategoryFilter.serializer()), list)

    fun decodeCategoryFilters(json: String?): List<CategoryFilter> =
        json?.takeIf { it.isNotBlank() }?.let { runCatching { TwitchJson.decodeFromString(ListSerializer(CategoryFilter.serializer()), it) }.getOrNull() }
            .orEmpty()

    /** Remembers [filter] for category [id] as the most recent one. Back to the default, it is forgotten instead. */
    fun withCategoryFilter(list: List<CategoryFilter>, id: String, filter: StreamFilter): List<CategoryFilter> {
        val others = list.filterNot { it.id == id }
        return if (filter == StreamFilter()) others else (others + CategoryFilter(id, filter)).takeLast(MAX_CATEGORY_FILTERS)
    }
}
