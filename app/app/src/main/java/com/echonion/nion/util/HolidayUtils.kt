package com.echonion.nion.util

import com.tyme.solar.SolarDay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间日期后缀工具 —— 为 LLM 上下文拼接带年月日、星期、节日、节气的时间标记。
 *
 * 设计要点：
 * - 后缀拼接到每条 user 消息末尾（而非 system prompt），保证 system 提示词完全稳定，
 *   从而命中 DeepSeek 的前缀缓存（Prefix Caching）。前缀缓存从 token 流开头计算最长相同前缀，
 *   一旦 system 中出现每次请求都变的动态内容，整段对话历史的缓存都会失效。
 * - 节日和节气用 🎉 前缀特别标出，便于模型识别当天特殊意义并主动问候。
 * - 数据来源 cn.6tail:tyme4kt（纯 Kotlin 农历库），异常时静默降级，绝不阻断发消息主流程。
 */
object HolidayUtils {

    /** 完整日期时间格式：2026年7月6日 星期一 14:30（Locale.CHINA 下 EEEE 输出 星期X） */
    private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)

    /**
     * 构造拼到 user 消息末尾的时间后缀。
     *
     * 示例：
     * - 平时：[时间：2026年7月6日 星期一 14:30]
     * - 节日：[时间：2026年10月1日 星期三 14:30 国庆节]
     * - 节气：[时间：2026年5月5日 星期二 08:00 立夏]
     * - 农历：[时间：2026年2月17日 星期二 00:00 春节]
     * - 多重：[时间：xxxx 国庆节 立秋]（同日既是节日又是节气时空格连接）
     *
     * @param date 日期，默认今天
     * @param time 时间，默认当前时刻
     * @return 形如 \n\n[时间：...] 的后缀字符串，可直接拼到消息文本末尾
     */
    fun buildTimeSuffix(
        date: LocalDate = LocalDate.now(),
        time: LocalTime = LocalTime.now(),
    ): String {
        // 取当天所有特别日子（节日+节气），多个用空格连接
        val specials = getSpecials(date)
        // 无特别日子则不加后缀段，保持纯净时间标记
        val specialPart = if (specials.isEmpty()) "" else " ${specials.joinToString(" ")}"
        // 用 LocalDateTime 一次性格式化日期+星期+时分（避免 LocalDate 缺时间字段的问题）
        return "\n\n[时间：${LocalDateTime.of(date, time).format(dateTimeFmt)}$specialPart]"
    }

    /**
     * 查询某天的特别日子（节日 + 节气当天），返回中文名称列表。
     *
     * 数据来源（cn.6tail:tyme4kt）：
     * - 公历现代节日（SolarFestival）：元旦、妇女节、劳动节、儿童节、国庆节、圣诞节 等
     * - 农历传统节日（LunarFestival）：春节、元宵节、端午节、七夕、中秋节、重阳节、除夕 等
     * - 二十四节气：仅当今天恰好是节气交节当日（getDayIndex()==0）才标出，如 立春、惊蛰、立夏
     *   （每天所属某节气，但只在交节当天标，避免每条消息都带节气造成噪音）
     *
     * @param date 要查询的日期
     * @return 特别日子名称列表，可能为空。示例：["国庆节"]、["立夏"]、["春节"]
     */
    fun getSpecials(date: LocalDate): List<String> {
        val result = mutableListOf<String>()
        try {
            // Tyme 公历日对象，是所有公历/农历/节气查询的统一入口
            val solarDay = SolarDay.fromYmd(date.year, date.monthValue, date.dayOfMonth)

            // 1. 公历现代节日（当天无节日返回 null）
            solarDay.getFestival()?.let { result.add(it.getName()) }

            // 2. 农历传统节日（先转农历日，再查节日；当天无节日返回 null）
            solarDay.getLunarDay().getFestival()?.let { result.add(it.getName()) }

            // 3. 二十四节气：getTerm() 返回当天所属节气，getSolarDay() 为该节气交节当日；
            //    两者为同一天时说明今天正好是节气当日，标出节气名（立春、惊蛰、立夏等）
            val term = solarDay.getTerm()
            if (term.getSolarDay().toString() == solarDay.toString()) {
                result.add(term.getName())
            }
        } catch (_: Exception) {
            // tyme4kt 抛异常时静默降级，返回空列表，不影响发消息主流程
        }
        return result
    }
}
