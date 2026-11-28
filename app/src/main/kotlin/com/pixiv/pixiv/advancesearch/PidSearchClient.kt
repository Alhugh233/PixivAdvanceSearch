package com.pixiv.pixiv.advancesearch

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class PidTarget {
    ILLUST,
    NOVEL,
    USER,
    ILLUST_SERIES,
    NOVEL_SERIES
}

internal data class PidSearchResult(
    val target: PidTarget,
    val id: Long,
    val title: String,
    val subtitle: String,
    val userId: Long? = null
)

internal class PidSearchException(message: String) : Exception(message)

internal object PidSearchClient {
    private const val API_BASE = "https://app-api.pixiv.net"
    private const val CLIENT_HASH_SECRET =
        "28c1fdd170a5204386cb1313c7077b34f83e4aaf4aa829ce78c231e05b0bae2c"
    private const val ACCOUNT_TYPE = "net.pixiv"
    private const val AUTH_TOKEN_TYPE = "unlimited"

    fun search(context: Context, pid: Long): List<PidSearchResult> {
        val token = accessToken(context)
            ?: throw PidSearchException("No Pixiv login token")
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "6.205.0"
        val headers = requestHeaders(token, appVersion)

        val results = ArrayList<PidSearchResult>(5)
        addResult(results, queryIllust(headers, pid))
        addResult(results, queryNovel(headers, pid))
        addResult(results, queryUser(headers, pid))
        addResult(results, queryIllustSeries(headers, pid))
        addResult(results, queryNovelSeries(headers, pid))
        android.util.Log.i("PixivAdvanceSearch", "PID search $pid: ${results.size} result(s)")
        return results
    }

    private fun addResult(results: MutableList<PidSearchResult>, result: PidSearchResult?) {
        if (result != null) results += result
    }

    private fun queryIllust(headers: Map<String, String>, pid: Long): PidSearchResult? {
        val root = getJson(
            headers,
            "/v1/illust/detail?filter=for_android&illust_id=$pid"
        ) ?: return null
        val illust = root.optJSONObject("illust") ?: return null
        val id = illust.optLong("id", pid)
        val user = illust.optJSONObject("user")
        return PidSearchResult(
            target = PidTarget.ILLUST,
            id = id,
            title = illust.optString("title").trim(),
            subtitle = userSubtitle(user)
        )
    }

    private fun queryNovel(headers: Map<String, String>, pid: Long): PidSearchResult? {
        val root = getJson(headers, "/v2/novel/detail?novel_id=$pid") ?: return null
        val novel = root.optJSONObject("novel") ?: return null
        val id = novel.optLong("id", pid)
        val user = novel.optJSONObject("user")
        return PidSearchResult(
            target = PidTarget.NOVEL,
            id = id,
            title = novel.optString("title").trim(),
            subtitle = userSubtitle(user)
        )
    }

    private fun queryUser(headers: Map<String, String>, pid: Long): PidSearchResult? {
        val root = getJson(
            headers,
            "/v2/user/detail?filter=for_android&user_id=$pid"
        ) ?: return null
        val user = root.optJSONObject("user") ?: return null
        val id = user.optLong("id", pid)
        val name = user.optString("name").trim()
        val account = user.optString("account").trim()
        return PidSearchResult(
            target = PidTarget.USER,
            id = id,
            title = name,
            subtitle = if (account.isBlank()) "" else "@$account"
        )
    }

    private fun queryIllustSeries(headers: Map<String, String>, pid: Long): PidSearchResult? {
        val root = getJson(
            headers,
            "/v1/illust/series?filter=for_android&illust_series_id=$pid"
        ) ?: return null
        val series = root.optJSONObject("illust_series_detail") ?: return null
        val id = series.optLong("id", pid)
        val user = series.optJSONObject("user")
        return PidSearchResult(
            target = PidTarget.ILLUST_SERIES,
            id = id,
            title = series.optString("title").trim(),
            subtitle = userSubtitle(user)
        )
    }

    private fun queryNovelSeries(headers: Map<String, String>, pid: Long): PidSearchResult? {
        val root = getJson(headers, "/v2/novel/series?series_id=$pid") ?: return null
        val series = root.optJSONObject("novel_series_detail") ?: return null
        val id = series.optLong("id", pid)
        val user = series.optJSONObject("user")
        return PidSearchResult(
            target = PidTarget.NOVEL_SERIES,
            id = id,
            title = series.optString("title").trim(),
            subtitle = userSubtitle(user),
            userId = user?.optLong("id")?.takeIf { it > 0L }
        )
    }

    private fun userSubtitle(user: JSONObject?): String {
        if (user == null) return ""
        val name = user.optString("name").trim()
        val account = user.optString("account").trim()
        return when {
            name.isNotEmpty() && account.isNotEmpty() -> "$name · @$account"
            name.isNotEmpty() -> name
            account.isNotEmpty() -> "@$account"
            else -> ""
        }
    }

    private fun getJson(headers: Map<String, String>, path: String): JSONObject? {
        val connection = (URL(API_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            useCaches = false
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                code == HttpURLConnection.HTTP_FORBIDDEN
            ) {
                throw PidSearchException("Pixiv login expired ($code)")
            }
            if (code !in 200..299) {
                throw PidSearchException("HTTP $code")
            }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun accessToken(context: Context): String? {
        return try {
            val manager = AccountManager.get(context)
            val account = manager.getAccountsByType(ACCOUNT_TYPE).firstOrNull() ?: return null
            manager.peekAuthToken(account, AUTH_TOKEN_TYPE)?.takeIf { it.isNotBlank() }
                ?: manager.blockingGetAuthToken(account, AUTH_TOKEN_TYPE, true)
                    ?.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            null
        }
    }

    private fun requestHeaders(token: String, appVersion: String): Map<String, String> {
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZZ", Locale.US).format(Date())
        val model = Build.MODEL.replace(Regex("[^\\x20-\\x7E]"), "?")
        return mapOf(
            "Authorization" to "Bearer $token",
            "User-Agent" to "PixivAndroidApp/$appVersion (Android ${Build.VERSION.RELEASE}; $model)",
            "App-OS" to "android",
            "App-OS-Version" to Build.VERSION.RELEASE,
            "App-Version" to appVersion,
            "Accept-Language" to Locale.getDefault().toString(),
            "X-Client-Time" to time,
            "X-Client-Hash" to md5(time + CLIENT_HASH_SECRET),
            "Referer" to "$API_BASE/"
        )
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
    }
}
