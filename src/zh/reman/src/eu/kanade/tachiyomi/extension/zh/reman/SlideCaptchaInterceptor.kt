package eu.kanade.tachiyomi.extension.zh.reman

import android.util.Log
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest

private const val TAG = "SlideCaptchaInterceptor"

// WAF 滑动验证挑战：被拦截的请求返回 403 + 一段引用 `/huadong_<siteId>_<key>.js` 的挑战页。
// 该 JS 里明文写着当前站点部署所用的 key/value（实测这组值会隔一段时间整体轮换一次，
// 不是每次挑战都变，也没有证据表明它跟挑战页下发的 session cookie 绑定——成功放行的
// 请求里那个 cookie 经常是空的）。把 value 按 stringToHex+MD5 处理后回传给验证接口即可
// 换到一个新 cookie（约 2 小时有效），凭这个 cookie 才能拿到真实响应。type 是站点级
// 固定常量，与 key/value 无关。
//
// 实测确认的关键点：JS 请求和验证请求必须带 `Referer`（指向当前页面）和真实浏览器
// `User-Agent`，裸请求（没有这两个头）哪怕算法/cookie 全对，源站也会拒绝放行——这是
// 之前反复踩坑最终定位到的真正原因，[forUrl] 负责把这些头从原始请求带过去。
//
// 也不能指望共享 OkHttpClient 配了持久化 CookieJar（实测 Suwayomi 这类非 Android 运行时
// 默认没有），所以这里手动把每一步下发的 Set-Cookie 收集起来，合并后显式带到下一步请求里。
//
// [bustCache] 只用在验证接口（专门的 PHP 脚本，实测能安全接受多余查询参数）上；不能
// 用来给真实内容页 URL 加缓存穿透参数——`/manhua/{id}.html` 能容忍陌生参数，但
// `/p/{mangaId}/{chapterId}.html` 这类章节页会因为参数把路由匹配打偏，返回 404 而不是
// 忽略参数，之前踩过这个坑（404 被误当成"已放行的最终结果"直接返回，导致看图失败）。
private val JS_CHALLENGE_REGEX = Regex("""src="(/huadong_[a-f0-9_]+\.js\?id=\d+)"""")
private val KEY_REGEX = Regex("""key="([a-f0-9]+)"""")
private val VALUE_REGEX = Regex("""value="([a-f0-9]+)"""")
private const val VERIFY_TYPE = "ad82060c2e67cc7e2cc47552a4fc1242"
private const val VERIFY_PATH = "/a20be899_96a6_40b2_88ba_32f1f75f1552_yanzheng_huadong.php"

// Response headers worth dumping whenever we log a step of this flow, to avoid another round
// trip of "what does the raw response actually look like" guessing.
private val DEBUG_HEADERS = listOf("Set-Cookie", "Cache-Control", "Date", "X-Via", "Content-Length")

class SlideCaptchaInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 403) return response
        Log.d(TAG, "challenged ${request.url} :: ${response.debugSummary()} :: requestHeaders=${request.headers}")

        val challengeHtml = response.peekBody(Long.MAX_VALUE).string()
        val jsPath = JS_CHALLENGE_REGEX.find(challengeHtml)?.groupValues?.get(1)
        var cookies = response.setCookiePairs()
        response.close()
        if (jsPath == null) {
            Log.d(TAG, "no JS challenge path found, giving up. cookies=$cookies body=$challengeHtml")
            return chain.proceed(request.withCookies(cookies))
        }
        Log.d(TAG, "jsPath=$jsPath cookiesFromChallenge=$cookies")

        val jsUrl = request.url.resolve(jsPath)
        if (jsUrl == null) {
            Log.d(TAG, "jsPath did not resolve against ${request.url}, giving up")
            return chain.proceed(request.withCookies(cookies))
        }
        val jsRequest = request.forUrl(jsUrl).withCookies(cookies)
        val jsResponse = chain.proceed(jsRequest)
        cookies = mergeCookies(cookies, jsResponse.setCookiePairs())
        val jsBody = jsResponse.use { it.body.string() }
        Log.d(TAG, "js ${jsResponse.debugSummary()} cookiesAfterJs=$cookies requestHeaders=${jsRequest.headers}")

        val key = KEY_REGEX.find(jsBody)?.groupValues?.get(1)
        val value = VALUE_REGEX.find(jsBody)?.groupValues?.get(1)
        if (key == null || value == null) {
            Log.d(TAG, "key/value not found in JS body, giving up. jsBody=$jsBody")
            return chain.proceed(request.withCookies(cookies))
        }
        val verificationValue = md5(stringToHex(value))
        Log.d(TAG, "key=$key value=$value verificationValue=$verificationValue")

        val verifyUrl = request.url.resolve("$VERIFY_PATH?type=$VERIFY_TYPE&key=$key&value=$verificationValue")!!.bustCache()
        val verifyResponse = chain.proceed(request.forUrl(verifyUrl).withCookies(cookies))
        val verifySetCookies = verifyResponse.setCookiePairs()
        val verifyBody = verifyResponse.use { it.body.string() }
        cookies = mergeCookies(cookies, verifySetCookies)
        Log.d(TAG, "verify ${verifyResponse.debugSummary()} body=$verifyBody mergedCookies=$cookies")

        val retryRequest = request.withCookies(cookies)
        val retryResponse = chain.proceed(retryRequest)
        Log.d(TAG, "retry ${retryResponse.debugSummary()} cookieSent=${retryRequest.header("Cookie")}")

        return retryResponse
    }

    /** 加一个随机查询参数换缓存键，强制绕开只按 URL 字面值缓存的前置代理 */
    private fun HttpUrl.bustCache(): HttpUrl = newBuilder().addQueryParameter("_", System.nanoTime().toString()).build()

    /**
     * 复制原始请求的完整请求头（User-Agent/Origin 等 KeiSource 默认头），只换 URL 并把
     * Referer 改成当前页面地址——之前这两个子请求是裸 `GET(url)` 建的，完全没有这些头，
     * 尤其是没有 Referer，跟真实浏览器加载页面内嵌 JS/XHR 时的请求特征差得远。
     */
    private fun Request.forUrl(url: HttpUrl): Request = newBuilder().url(url).header("Referer", this.url.toString()).build()

    /** 状态码 + 关键响应头，用于排查日志，避免下次还要再来一轮猜测 */
    private fun Response.debugSummary(): String {
        val headerDump = DEBUG_HEADERS.joinToString(" ") { name -> "$name=${headers(name).ifEmpty { listOf("-") }}" }
        return "code=$code $headerDump"
    }

    /** `Set-Cookie` 响应头里每条 `name=value` 部分（忽略 Path/HttpOnly 等属性） */
    private fun Response.setCookiePairs(): List<String> = headers("Set-Cookie").map { it.substringBefore(";") }

    /** 按 cookie 名去重合并，[extra] 里的值覆盖 [base] 里的同名值 */
    private fun mergeCookies(base: List<String>, extra: List<String>): List<String> {
        val merged = LinkedHashMap<String, String>()
        for (cookie in base + extra) merged[cookie.substringBefore("=")] = cookie
        return merged.values.toList()
    }

    /**
     * 把收集到的 cookie 合并进请求已有的 `Cookie` 头（同名以 [cookies] 为准）。FORCE_NETWORK
     * 带的 `Cache-Control: no-cache` 对前置缓存代理没用（实测它不遵守标准语义，真正生效的
     * 是补全 Referer/UA），留着只是防止 OkHttp 自己的本地缓存返回这套一次性验证流程里的
     * 陈旧响应，无害。
     */
    private fun Request.withCookies(cookies: List<String>): Request {
        val builder = newBuilder().cacheControl(CacheControl.FORCE_NETWORK)
        if (cookies.isNotEmpty()) {
            val existing = header("Cookie")?.split("; ").orEmpty()
            val merged = mergeCookies(existing, cookies)
            builder.header("Cookie", merged.joinToString("; "))
        }
        return builder.build()
    }

    /** 每个字符的 ASCII 码 +1 后按十进制拼接 */
    private fun stringToHex(text: String): String = buildString {
        text.forEach { append(it.code + 1) }
    }

    private fun md5(text: String): String {
        val hash = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
