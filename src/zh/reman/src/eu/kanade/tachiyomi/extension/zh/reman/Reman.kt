package eu.kanade.tachiyomi.extension.zh.reman

import android.util.Base64
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private const val QUICK_SEARCH_PREFIX = "var success_data="

// Cover/page images live under varying upload dirs (seen: /upload/, /uploadPic/), unlike the
// slide-captcha-guarded HTML routes, so they're exempted from the rate limit below by extension
// rather than by directory prefix.
private val IMAGE_PATH_REGEX = Regex("""\.(jpe?g|png|gif|webp)$""", RegexOption.IGNORE_CASE)

/**
 * 热漫（漫画大全）— 老式 qTcms 桌面模板（页面声明 GB2312，实际需按 GB18030 解码，见
 * [asGbkJsoup]；ASP 后端）。当前域名由 reman.cc 地址发布页动态发现（真实域名会不定期
 * 更换，模块名不跟着当前域名走）。
 *
 * 该站点用 WAF 滑动验证码拦截了搜索、分类导航、排行榜、字母索引、"全部漫画" 等接口
 * （403 + 挑战页），[SlideCaptchaInterceptor] 会自动解算挑战并换取放行 cookie，对所有
 * 请求透明重试；验证通过后拿到的 cookie 对分类导航（`/mmm/{id}/`）等路径有效期约 2
 * 小时，因此分类筛选已改为调用真实分类接口并支持翻页。
 *
 * 站点自带的 `search.asp` 表单搜索接口本身是坏的——不管验证码是否通过、关键词是什么
 * （含空关键词），一律固定返回 6 字节的 `soyyr!`，不是正常的结果页，因此未接入。关键
 * 词搜索改用移动版页面 (`m.yueman1.cc`) 背后同款的 JSON 快速搜索接口——该接口实际挂
 * 在 PC 域名 `www.yueman1.cc` 下（跟移动版页面本身是两个不同的主机），同样受滑动验证
 * 码保护，关键词需按 GBK 而非 UTF-8 编码提交，返回的漫画 ID 与 PC 站 `/manhua/{id}.html`
 * 通用。
 *
 * 章节图片直接内嵌在章节页的 `qTcms_S_m_murl_e` 内联脚本变量中（Base64，用
 * `$qingtiandy$` 分隔），无需逐页翻页请求。
 *
 * [UpdateUrlInterceptor] 会在请求失败时自动从 reman.cc 发现新域名并持久化。
 *
 * 站点的滑块验证码看起来是按请求频率概率触发的，因此对当前域名的页面请求做了限速
 * （2 个/秒）降低触发概率；但验证码只出现在页面路由上、从未出现在图片资源上，所以
 * 翻页看图的请求豁免限速，不影响阅读体验。
 */
@Source
abstract class Reman : KeiSource() {

    private val preferences = getPreferences()

    // 页面声明的 charset 是 gb2312，但实际会发繁体字等超出 GB2312 范围的字节（比如章节名
    // "血脈賁張"），按声明值解码会把这些字符变成乱码；强制用 GB18030（GBK 的超集）解码。
    private fun Response.asGbkJsoup(): Document = use { response ->
        Jsoup.parse(response.body.byteStream(), "GB18030", response.request.url.toString())
    }

    /**
     * 某个页面结构假设（选择器/响应格式）不成立时统一抛这个，而不是静默返回空列表——异常
     * 信息里带上 URL 和实际收到的原始内容片段，方便直接复制反馈排查（比如能立刻看出是命中
     * 了没解开的验证码挑战页，还是站点真的改版了），不用另外开 debug log 重跑一遍。
     */
    private fun parseError(what: String, url: String, content: String): Nothing {
        val snippet = content.replace(Regex("\\s+"), " ").take(800)
        throw Exception("[$what] 解析失败 url=$url 响应片段=$snippet")
    }

    private fun Document.parseError(what: String): Nothing = parseError(what, location(), html())

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(UpdateUrlInterceptor(preferences))
        .addInterceptor(SlideCaptchaInterceptor())
        .rateLimit(2) { it.host == baseUrl.toHttpUrl().host && !IMAGE_PATH_REGEX.containsMatchIn(it.encodedPath) }

    // ---- 卡片解析 ----

    /** 首页 "热门连载漫画" 等 tab 卡片 */
    private fun Element.toCardSManga(): SManga = SManga.create().apply {
        val link = select("a[href~=/manhua/\\d+\\.html]").last()!!
        url = link.attr("href")
        title = link.attr("title").ifBlank { link.text() }
        thumbnail_url = selectFirst("img")?.let { it.attr("_src").ifBlank { it.attr("src") } }
    }

    /** "最新更新" (m_recent.html) 列表项 */
    private fun Element.toUpdateSManga(): SManga = SManga.create().apply {
        val link = selectFirst("a.video")!!
        url = link.attr("href")
        title = link.text()
        thumbnail_url = link.attr("i")
    }

    /** "完结漫画" (m_wanjie.html) 字母索引项，无封面 */
    private fun Element.toIndexSManga(): SManga = SManga.create().apply {
        url = attr("href")
        title = text()
    }

    // ---- 热门（首页 "热门连载漫画" tab，无分页） ----

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = client.get(baseUrl).asGbkJsoup()
        val container = document.selectFirst("#icmd_list > ul") ?: document.parseError("首页热门列表 #icmd_list")
        return MangasPage(container.select("li").map { it.toCardSManga() }, false)
    }

    // ---- 最新（m_recent.html，无分页） ----

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = client.get("$baseUrl/manhua/o/m_recent.html").asGbkJsoup()
        val container = document.selectFirst("div.updateList") ?: document.parseError("最新更新列表 div.updateList")
        return MangasPage(container.select("li").map { it.toUpdateSManga() }, false)
    }

    // ---- 分类浏览（/mmm/{id}/，选中分类后忽略搜索框，走真实分页） ----

    private class CategoryFilter :
        Filter.Select<String>(
            "分类（选中后忽略搜索框，直接翻页浏览）",
            arrayOf(
                "不限（按标题搜索\"最新更新\"/\"完结漫画\"）",
                "少年热血", "武侠格斗", "科幻魔幻", "竞技体育", "爆笑喜剧",
                "侦探推理", "恐怖灵异", "少女爱情", "恋爱生活", "生活漫画",
            ),
        )

    /** 与上面 CategoryFilter 选项一一对应（跳过原站没有的 8 号分类） */
    private val categoryIds = intArrayOf(1, 2, 3, 4, 5, 6, 7, 9, 10, 11)

    override fun getFilterList(data: JsonElement?) = FilterList(
        CategoryFilter(),
        Filter.Header("选中分类后忽略搜索框；留空分类时按关键词调用站点的快速搜索接口"),
    )

    // ---- 搜索：选中分类走真实分页接口，否则调用移动版页面同款的 JSON 快速搜索接口 ----
    // （该接口挂在 PC 域名下，跟其余接口一样受 [SlideCaptchaInterceptor] 保护；关键词
    // 必须按页面声明的 GBK 编码提交，返回的漫画 ID 与 PC 站 /manhua/{id}.html 通用）

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val categoryIndex = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.state ?: 0
        if (categoryIndex > 0) return getCategoryMangaList(categoryIds[categoryIndex - 1], page)

        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            if (page > 1) return MangasPage(emptyList(), false)
            val recent = client.get("$baseUrl/manhua/o/m_recent.html").asGbkJsoup()
                .select("div.updateList li").map { it.toUpdateSManga() }
            val completed = client.get("$baseUrl/manhua/o/m_wanjie.html").asGbkJsoup()
                .select("a[href~=/manhua/\\d+\\.html]").map { it.toIndexSManga() }
            return MangasPage((recent + completed).distinctBy { it.url }, false)
        }

        if (page > 1) return MangasPage(emptyList(), false)
        return MangasPage(quickSearch(trimmedQuery), false)
    }

    // Suwayomi's dex2jar-converted jar can't load the `@Serializable`-generated
    // `GeneratedSerializer` companion classes (NoClassDefFoundError at runtime, same class of
    // dex2jar fragility as the R8 note in build.gradle.kts), so this is parsed with plain
    // org.json instead of kotlinx.serialization.
    private suspend fun quickSearch(query: String): List<SManga> {
        val keyGbk = query.toByteArray(charset("GBK")).joinToString("") { "%%%02X".format(it.toInt() and 0xFF) }
        val searchUrl = "$baseUrl/template/skin4_20110501/images/m/html/?a=3&key=$keyGbk"
        val body = client.get(searchUrl).body.string()
        // Response is a `var success_data=<json>` statement, not bare JSON; if the WAF challenge
        // slips through instead, the body won't have this prefix at all.
        if (!body.startsWith(QUICK_SEARCH_PREFIX)) parseError("快速搜索接口", searchUrl, body)
        val data = JSONObject(body.removePrefix(QUICK_SEARCH_PREFIX).trim()).getJSONArray("data")
        return (0 until data.length()).map { i ->
            val item = data.getJSONObject(i)
            SManga.create().apply {
                url = item.getString("url").toHttpUrl().encodedPath
                title = item.getString("name")
                thumbnail_url = item.getString("cover")
            }
        }
    }

    private suspend fun getCategoryMangaList(categoryId: Int, page: Int): MangasPage {
        val path = if (page == 1) "/mmm/$categoryId/" else "/mmm/$categoryId/$page.html"
        val document = client.get("$baseUrl$path").asGbkJsoup()
        val container = document.selectFirst("#lcmd_list") ?: document.parseError("分类列表 #lcmd_list")
        val totalPages = document.selectFirst("#pager .total strong")?.text()?.toIntOrNull() ?: page
        return MangasPage(container.select("li").map { it.toCardSManga() }, page < totalPages)
    }

    // ---- 详情与章节（同一详情页，只请求一次） ----

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asGbkJsoup()
        return SMangaUpdate(mangaDetails(document), chapterList(document))
    }

    private fun mangaDetails(document: Document): SManga = SManga.create().apply {
        url = document.location().toHttpUrl().encodedPath
        title = document.selectFirst("div.title h1")?.text() ?: document.parseError("漫画详情标题 div.title h1")
        thumbnail_url = document.selectFirst("div.info_cover img")?.attr("src")
        author = document.select("div.info p:has(em:contains(原著作者：))").firstOrNull()?.ownText()
        genre = document.selectFirst("div.info p:has(em:contains(剧情类别：)) a")?.text()
        description = document.select("div.introduction").firstOrNull()?.text()
            ?.substringBefore("免费漫画大全为您提供")
            ?.trim(',', '，', ' ')
        status = SManga.UNKNOWN
    }

    // 站点原始顺序是新章节在前；保持这个顺序不反转，这样 Tachiyomi/Suwayomi 默认按扩展返回
    // 顺序展示时，列表顶部就是最新章节（reverse 整个列表会导致最旧的排到最上面）。
    // chapter_number 仍按"从旧到新递增"计算（最旧=1，最新=总数），跟列表顺序无关。
    private fun chapterList(document: Document): List<SChapter> {
        val elements = document.select("div#play_0 ul li a")
        val total = elements.size
        return elements.mapIndexed { index, a ->
            SChapter.create().apply {
                url = a.attr("href")
                name = a.attr("title").ifBlank { a.text() }
                chapter_number = (total - index).toFloat()
            }
        }
    }

    // ---- URL 搜索（在搜索框粘贴站点链接） ----

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!Regex("/manhua/\\d+\\.html").matches(url.encodedPath)) return null
        val document = client.get(url.toString()).asGbkJsoup()
        return mangaDetails(document)
    }

    // ---- 正文：图片地址直接内嵌在章节页的内联脚本里 ----

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asGbkJsoup()
        val script = document.select("script").map { it.data() }
            .firstOrNull { "qTcms_S_m_murl_e=" in it } ?: document.parseError("章节页图片脚本 qTcms_S_m_murl_e")
        val base64 = Regex("qTcms_S_m_murl_e=\"([^\"]*)\"").find(script)?.groupValues?.get(1)
        if (base64.isNullOrBlank()) document.parseError("章节页图片脚本 qTcms_S_m_murl_e（值为空）")
        val decoded = String(Base64.decode(base64, Base64.DEFAULT))
        val imageUrls = decoded.split("\$qingtiandy\$").filter { it.isNotBlank() }
        // Catches our own decoding bugs (bad split/base64 offset etc.) as a loud, diagnosable
        // failure right here, distinct from a well-formed URL that's merely dead/unreachable at
        // fetch time (a site content-rot issue the framework's own network exception already
        // reports with the failing host, nothing for us to add there).
        val malformed = imageUrls.filter { it.toHttpUrlOrNull() == null }
        if (malformed.isNotEmpty()) {
            parseError(
                "章节页图片地址格式异常（非法 URL，可能是 base64/分隔符解析出错）",
                document.location(),
                "共${imageUrls.size}条，格式异常的有：$malformed；完整解码结果：$imageUrls",
            )
        }
        return imageUrls.mapIndexed { i, imageUrl -> Page(i, imageUrl = imageUrl) }
    }
}
