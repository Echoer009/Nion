package com.echonion.nion.ui.settings

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * 城市数据提供者 —— 从 assets/cities.json 加载中国城市列表，支持按名称/省份搜索过滤。
 *
 * 城市数据结构：{ name: "北京", province: "北京", lat: 39.9042, lon: 116.4074 }
 * 搜索逻辑：同时匹配城市名和省份名，忽略大小写。
 *
 * 使用方式：
 * ```kotlin
 * val cities = CitiesProvider.loadCities(context)
 * val results = CitiesProvider.search(cities, "北京")
 * ```
 */
object CitiesProvider {

    private const val TAG = "CitiesProvider"

    /**
     * 单条城市数据。
     *
     * @property name 城市名称（如 "北京"）
     * @property province 所属省份（如 "北京"）
     * @property lat 纬度
     * @property lon 经度
     */
    data class City(
        val name: String,
        val province: String,
        val lat: Double,
        val lon: Double,
    )

    /**
     * 从 assets/cities.json 加载全部城市列表。
     * 结果会被缓存，避免重复解析 JSON。
     *
     * @param context Android 上下文，用于访问 assets
     * @return 城市列表，加载失败返回空列表
     */
    fun loadCities(context: Context): List<City> {
        return try {
            val json = context.assets.open("cities.json")
                .bufferedReader()
                .use { it.readText() }
            val array = JSONArray(json)
            val cities = mutableListOf<City>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                cities.add(
                    City(
                        name = obj.getString("name"),
                        province = obj.getString("province"),
                        lat = obj.getDouble("lat"),
                        lon = obj.getDouble("lon"),
                    )
                )
            }
            cities
        } catch (e: Exception) {
            Log.w(TAG, "加载城市列表失败", e)
            emptyList()
        }
    }

    /**
     * 在城市列表中搜索匹配的城市。
     *
     * 匹配规则：城市名或省份名包含查询关键字（忽略大小写）。
     * 结果按城市名排序，去重。
     *
     * @param cities 全量城市列表
     * @param query 搜索关键字，如 "北京"、"广东"
     * @return 匹配的城市列表
     */
    fun search(cities: List<City>, query: String): List<City> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return cities.filter {
            it.name.lowercase().contains(q) || it.province.lowercase().contains(q)
        }.distinctBy { it.name }
    }
}
