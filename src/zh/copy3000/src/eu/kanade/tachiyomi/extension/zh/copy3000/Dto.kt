package eu.kanade.tachiyomi.extension.zh.copy3000

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.getArray
import keiyoushi.utils.getArrayOrNull
import keiyoushi.utils.getInt
import keiyoushi.utils.getObject
import keiyoushi.utils.getString
import keiyoushi.utils.getStringOrNull
import keiyoushi.utils.jsonInstance
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Locale

// kotlinx.serialization's @Serializable classes generate a synthetic
// "seen1 bitmask" constructor for deserialization. That constructor is a
// reliable VerifyError trigger when Suwayomi converts the apk (DEX) back to
// JVM bytecode via dex2jar, since dex2jar doesn't always emit a correct
// StackMapTable for it. Parsing raw JsonObject/JsonArray by hand avoids the
// compiler plugin entirely, so no such constructor exists in the first place.

fun String.parseResultsObject(): JsonObject = jsonInstance.parseToJsonElement(this).jsonObject.getObject("results")

class PageResult(json: JsonObject) {
    private val total = json.getInt("total")
    private val limit = json.getInt("limit")
    private val offset = json.getInt("offset")
    val list: JsonArray = json.getArray("list")
    val hasNextPage get() = offset + limit < total
}

class AuthorInfo(json: JsonObject) {
    val name: String = json.getString("name")
}

class ThemeInfo(json: JsonObject) {
    val name: String = json.getString("name")
}

class ComicInfo(json: JsonObject) {
    private val name = json.getString("name")
    private val pathWord = json.getString("path_word")
    private val cover = json.getStringOrNull("cover")
    private val author = json.getArrayOrNull("author")?.map { AuthorInfo(it.jsonObject) }.orEmpty()
    private val theme = json.getArrayOrNull("theme")?.map { ThemeInfo(it.jsonObject) }.orEmpty()

    fun toSManga() = SManga.create().apply {
        url = "/comic/$pathWord"
        title = name
        author = this@ComicInfo.author.joinToString { it.name }
        genre = theme.joinToString { it.name }
        thumbnail_url = cover
        initialized = false
    }
}

class DetailComicInfo(json: JsonObject) {
    private val name = json.getString("name")
    private val pathWord = json.getString("path_word")
    private val cover = json.getStringOrNull("cover")
    private val author = json.getArrayOrNull("author")?.map { AuthorInfo(it.jsonObject) }.orEmpty()
    private val theme = json.getArrayOrNull("theme")?.map { ThemeInfo(it.jsonObject) }.orEmpty()
    private val brief = json.getStringOrNull("brief")
    private val statusValue = json.getObject("status").getInt("value")

    fun toSManga() = SManga.create().apply {
        url = "/comic/$pathWord"
        title = name
        author = this@DetailComicInfo.author.joinToString { it.name }
        genre = theme.joinToString { it.name }
        description = brief.orEmpty()
        thumbnail_url = cover
        status = when (statusValue) {
            0 -> SManga.ONGOING
            1 -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }
}

class GroupInfo(json: JsonObject) {
    val pathWord: String = json.getString("path_word")
    val name: String = json.getString("name")
}

class DetailInfo(json: JsonObject) {
    val comic = DetailComicInfo(json.getObject("comic"))
    val groups = json.getObject("groups").values.map { GroupInfo(it.jsonObject) }
}

class ChapterInfo(json: JsonObject) {
    private val uuid = json.getString("uuid")
    val name: String = json.getString("name")
    val index: Int = json.getInt("index")
    private val comicPathWord = json.getString("comic_path_word")
    private val groupPathWord = json.getStringOrNull("group_path_word")
    private val datetimeCreated = json.getStringOrNull("datetime_created")

    fun toSChapter(groupName: String?) = SChapter.create().apply {
        url = "$comicPathWord/chapter/$uuid"
        name = this@ChapterInfo.name
        date_upload = datetimeCreated?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L
        if (groupPathWord != null && groupPathWord != "default") {
            scanlator = groupName
        }
    }

    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
    }
}
