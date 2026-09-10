package eu.kanade.tachiyomi.extension.zh.reman

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.asJsoup
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

private const val DEFAULT_BASE_URL = "http://www.yueman1.cc"

// Same pref key the DSL's `baseUrl { custom(...) }` codegen uses, so this interceptor
// and the generated manual-override preference share state.
private const val BASE_URL_PREF = "overrideBaseUrl"

// A stable "domain announcement" page the site operator keeps up to date whenever the
// real domain changes; it lists the current PC ("PC电脑") and WAP ("WAP手机") hosts.
private const val DOMAIN_ANNOUNCEMENT_URL = "http://reman.cc/"

var SharedPreferences.baseUrl: String
    get() = getString(BASE_URL_PREF, DEFAULT_BASE_URL)!!
    set(value) = edit().putString(BASE_URL_PREF, value).apply()

class UpdateUrlInterceptor(
    private val preferences: SharedPreferences,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val baseUrl = preferences.baseUrl
        if (!url.toString().startsWith(baseUrl)) return chain.proceed(request)

        // Kept open until we know we won't be returning it: closing it eagerly here would
        // make a later `return failedResult` hand the caller an already-closed Response.
        val failedResult = try {
            val response = chain.proceed(request)
            if (response.isSuccessful) return response
            Result.success(response)
        } catch (e: IOException) {
            if (chain.call().isCanceled()) throw e
            Result.failure(e)
        }

        val newUrl = try {
            val document = chain.proceed(GET(DOMAIN_ANNOUNCEMENT_URL)).asJsoup()
            val discovered = document.select("li:has(span:containsOwn(PC电脑))")
                .first()!!.attr("data-url").removeSuffix("/")
            require(discovered.isNotBlank() && discovered != baseUrl)
            val (scheme, host) = discovered.split("://").let { it[0] to it[1] }
            Triple(discovered, scheme, host)
        } catch (_: Throwable) {
            null
        } ?: run {
            failedResult.getOrNull()?.let { return it }
            throw failedResult.exceptionOrNull()!!
        }

        failedResult.getOrNull()?.close()
        val (newBaseUrl, scheme, host) = newUrl
        preferences.baseUrl = newBaseUrl
        val retryUrl = url.newBuilder().scheme(scheme).host(host).build()
        val retryRequest = request.newBuilder().url(retryUrl).build()
        return chain.proceed(retryRequest)
    }
}
