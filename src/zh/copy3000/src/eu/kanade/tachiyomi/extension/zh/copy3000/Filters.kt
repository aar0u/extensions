package eu.kanade.tachiyomi.extension.zh.copy3000

import eu.kanade.tachiyomi.source.model.Filter

abstract class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(
    name,
    options.map { it.first }.toTypedArray(),
) {
    val selected get() = options[state].second
}

class SortFilter :
    SelectFilter(
        name = "排序",
        options = listOf(
            "熱度" to "-popular",
            "更新時間" to "-datetime_updated",
        ),
    )
