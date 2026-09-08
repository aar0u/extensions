package eu.kanade.tachiyomi.extension.zh.copy3000

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getArray
import keiyoushi.utils.getObject
import keiyoushi.utils.getPreferences
import keiyoushi.utils.getString
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Copymanga :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences = getPreferences()

    private val apiHost by lazy { "api." + baseUrl.toHttpUrl().host.removePrefix("www.") }

    private val apiUrl by lazy { "https://$apiHost/api/v3" }

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(10, 1.seconds) { it.host == apiHost }
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Accept", "application/json")
        .set("Origin", "https://copy20.com")
        .set("Version", "2025.05.09")
        .set("Platform", "1")
        .set("Region", "0")
        .set("Webp", "1")

    private fun apiGet(url: String): Request {
        val newUrl = url.toHttpUrl().newBuilder()
            .addQueryParameter("_update", "true")
            .build()
        return GET(newUrl, headers)
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int) = comicListRequest(page, "-popular")

    override fun popularMangaParse(response: Response) = comicListParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) = apiGet("$apiUrl/update/newest?limit=$PAGE_SIZE&offset=${(page - 1) * PAGE_SIZE}")

    override fun latestUpdatesParse(response: Response): MangasPage {
        val page = PageResult(response.body.string().parseResultsObject())
        val mangas = page.list.map { ComicInfo(it.jsonObject.getObject("comic")).toSManga() }
        return MangasPage(mangas, page.hasNextPage)
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$apiUrl/search/comic".toHttpUrl().newBuilder()
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
                .addQueryParameter("q", query)
                .addQueryParameter("q_type", "")
                .build()
            return apiGet(url.toString())
        }

        val ordering = filters.firstInstance<SortFilter>().selected
        return comicListRequest(page, ordering)
    }

    override fun searchMangaParse(response: Response) = comicListParse(response)

    private fun comicListRequest(page: Int, ordering: String): Request {
        val url = "$apiUrl/comics".toHttpUrl().newBuilder()
            .addQueryParameter("free_type", "1")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("ordering", ordering)
            .build()
        return apiGet(url.toString())
    }

    private fun comicListParse(response: Response): MangasPage {
        val page = PageResult(response.body.string().parseResultsObject())
        val mangas = page.list.map { ComicInfo(it.jsonObject).toSManga() }
        return MangasPage(mangas, page.hasNextPage)
    }

    override fun getFilterList() = FilterList(SortFilter())

    // ============================== Details ==============================

    override fun mangaDetailsRequest(manga: SManga) = apiGet("$apiUrl/comic2/${manga.url.substringAfterLast("/")}")

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        val detail = DetailInfo(response.body.string().parseResultsObject())
        return detail.comic.toSManga()
    }

    // ============================== Chapters ==============================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val pathWord = manga.url.substringAfterLast("/")
        val detail = DetailInfo(
            client.newCall(apiGet("$apiUrl/comic2/$pathWord")).execute().body.string().parseResultsObject(),
        )
        val hiddenNames = hideDefaultContinuousChapter.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        detail.groups.flatMap { group ->
            val chapters = mutableListOf<ChapterInfo>()
            var offset = 0
            while (true) {
                val page = PageResult(
                    client.newCall(
                        apiGet("$apiUrl/comic/$pathWord/group/${group.pathWord}/chapters?limit=$CHAPTER_PAGE_SIZE&offset=$offset"),
                    ).execute().body.string().parseResultsObject(),
                )
                chapters += page.list.map { ChapterInfo(it.jsonObject) }
                if (!page.hasNextPage) break
                offset += CHAPTER_PAGE_SIZE
            }
            chapters
                .filterNot { it.name in hiddenNames }
                .sortedWith(compareByDescending { it.index })
                .map { it.toSChapter(group.name) }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = throw UnsupportedOperationException()

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/comic/${chapter.url}"

    // ================================ Pages ================================

    // chapter.url is "$pathWord/chapter/$uuid" (see ChapterInfo.toSChapter);
    // the content API lives at "$pathWord/chapter2/$uuid" instead.
    override fun pageListRequest(chapter: SChapter) = apiGet("$apiUrl/comic/${chapter.url.replace("/chapter/", "/chapter2/")}")

    override fun pageListParse(response: Response): List<Page> {
        val chapter = response.body.string().parseResultsObject().getObject("chapter")
        val contents = chapter.getArray("contents")
        return contents.mapIndexed { index, content -> Page(index, imageUrl = content.jsonObject.getString("url")) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page) = GET(page.imageUrl!!)

    // ============================== Preferences ==============================

    private val hideDefaultContinuousChapter: String
        get() = preferences.getString(HIDE_CONTINUOUS_CHAPTER_PREF, "")!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = HIDE_CONTINUOUS_CHAPTER_PREF
            title = "隱藏預設連載章節"
            summary = "部分作品的預設連載章節陳舊，新章節更新在其他分組中；在這裡填寫章節名稱（一行一個）即可在獲取章節時隱藏"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    companion object {
        private const val PAGE_SIZE = 21
        private const val CHAPTER_PAGE_SIZE = 500
        private const val HIDE_CONTINUOUS_CHAPTER_PREF = "hideDefaultContinuousChapter"
    }
}
