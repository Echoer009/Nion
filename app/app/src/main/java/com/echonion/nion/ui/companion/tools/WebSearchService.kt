package com.echonion.nion.ui.companion.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * 网页搜索服务 —— Bing 国际版。
 *
 * 使用 www.bing.com（国际版），Accept-Language: en-US，搜索词必须为英文。
 * Bing 中国版对中文查询存在字典模式 bug（搜索"拼多多"返回"拼"的字典释义），
 * 国际版 + en-US 可避免此问题，但要求搜索词使用英文。
 *
 * 返回 JSON 格式：
 * ```json
 * {
 *   "results": [{"title": "...", "url": "...", "snippet": "..."}],
 *   "count": 5,
 *   "query": "search query",
 *   "engine": "bing"
 * }
 * ```
 */
object WebSearchService {

    private const val TAG = "WebSearchService"

    /** Bing 搜索端点 —— 国际版 */
    private const val BING_URL = "https://www.bing.com/search"

    /** 伪装 Chrome User-Agent，避免被搜索引擎拦截 */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 最大返回结果数 */
    private const val MAX_RESULTS = 10

    /** OkHttp 客户端，10 秒超时 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 执行网页搜索 —— Bing 国际版。
     *
     * 搜索词必须为英文（由 WebSearchTool 提示词约束 LLM），
     * 因为 Bing 对中文查询存在字典模式 bug，无法通过 URL 参数关闭。
     *
     * @param query 搜索关键词（英文）
     * @return JSON 字符串，包含 results / count / query / engine 字段
     */
    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val result = searchBing(query)
        val results = parseJsonResults(result)
        if (results.length() > 0) {
            Log.d(TAG, "Bing 搜索成功: query=$query, results=${results.length()}")
            return@withContext result
        }

        Log.w(TAG, "Bing 无结果: query=$query")
        buildErrorJson(query, "未找到相关搜索结果")
    }

    /**
     * 通过 Bing 国际版搜索。
     *
     * 请求配置：
     * - URL：`www.bing.com/search?q=关键词`（无额外参数）
     * - Accept-Language: en-US（避免中文查询触发字典模式）
     * - HTML 解析选择器：`ol#b_results > li.b_algo`
     *
     * @param query 搜索关键词
     * @return JSON 字符串，包含 engine="bing"
     */
    private fun searchBing(query: String): String {
        return try {
            // 纯净配置：www.bing.com + en-US，无 cc/setlang/filters 等参数
            // 这些参数经实测均无法解决 Bing 对中文查询的字典模式 bug
            val url = BING_URL.toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()

            Log.d(TAG, "Bing 搜索请求: query=$query, url=$url")

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body.isNullOrBlank()) {
                Log.e(TAG, "Bing 请求失败: code=${response.code}")
                return buildErrorJson(query, "Bing 搜索失败 (HTTP ${response.code})")
            }

            val results = parseBingResults(body)
            Log.d(TAG, "Bing 解析完成: results=${results.length()}")
            // 打印前 3 条结果标题，便于排查
            for (i in 0 until minOf(3, results.length())) {
                Log.d(TAG, "Bing result[$i]: title=${results.getJSONObject(i).optString("title", "")}")
            }

            // DEBUG: 解析为空时打印 HTML 诊断信息
            if (results.length() == 0) {
                val doc = Jsoup.parse(body)
                val bResults = doc.select("ol#b_results")
                Log.w(TAG, "Bing DEBUG: b_results 存在=${bResults.size}, html长度=${body.length}")
                if (bResults.isNotEmpty()) {
                    Log.w(TAG, "Bing DEBUG: b_results 子元素: ${bResults[0].children().take(3).map { "${it.tagName()}.${it.className()}" }}")
                }
                Log.w(TAG, "Bing DEBUG: li.b_algo 数量=${doc.select("li.b_algo").size}")
                Log.w(TAG, "Bing DEBUG: title=${doc.title().take(100)}")
            }

            JSONObject().apply {
                put("results", results)
                put("count", results.length())
                put("query", query)
                put("engine", "bing")
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Bing 搜索异常: query=$query", e)
            buildErrorJson(query, "Bing 搜索失败: ${e.message}")
        }
    }

    /**
     * 用 Jsoup 解析 Bing HTML 响应，提取搜索结果。
     *
     * 选择器：
     * - `ol#b_results > li.b_algo` —— 每个搜索结果
     * - `li.b_algo > h2 > a` —— 标题链接（text=标题，href=URL）
     * - `li.b_algo div.b_caption p` —— 摘要段落
     *
     * @param html Bing 返回的 HTML 字符串
     * @return 搜索结果的 JSON 数组
     */
    private fun parseBingResults(html: String): JSONArray {
        val doc = Jsoup.parse(html)
        val results = JSONArray()

        val resultElements = doc.select("ol#b_results > li.b_algo")

        for (element in resultElements) {
            if (results.length() >= MAX_RESULTS) break

            // 标题和链接：h2 > a
            val titleLink = element.selectFirst("h2 > a") ?: continue
            val title = titleLink.text().trim()
            if (title.isEmpty()) continue

            // 提取 URL（Bing 的 href 直接就是目标 URL，无需清洗）
            val url = titleLink.attr("abs:href").takeIf { it.isNotEmpty() }
                ?: titleLink.attr("href").takeIf { it.startsWith("http") }
                ?: continue

            // 摘要：div.b_caption 下的 p 标签
            val snippetEl = element.selectFirst("div.b_caption p")
            val snippet = snippetEl?.text()?.trim() ?: ""

            results.put(JSONObject().apply {
                put("title", title)
                put("url", url)
                put("snippet", snippet)
            })
        }

        return results
    }

    /**
     * 从 JSON 搜索结果字符串中提取 results 数组。
     *
     * @param json 搜索结果 JSON 字符串
     * @return results JSON 数组，解析失败返回空数组
     */
    private fun parseJsonResults(json: String): JSONArray {
        return try {
            JSONObject(json).optJSONArray("results") ?: JSONArray()
        } catch (_: Exception) {
            JSONArray()
        }
    }

    /**
     * 构建错误响应 JSON。
     *
     * @param query 原始搜索词
     * @param errorMsg 错误描述
     * @return JSON 字符串
     */
    private fun buildErrorJson(query: String, errorMsg: String): String {
        return JSONObject().apply {
            put("error", errorMsg)
            put("results", JSONArray())
            put("count", 0)
            put("query", query)
        }.toString()
    }
}
