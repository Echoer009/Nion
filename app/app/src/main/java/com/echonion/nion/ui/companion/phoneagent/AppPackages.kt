package com.echonion.nion.ui.companion.phoneagent

/**
 * 应用名称 → 包名映射表。
 *
 * 从 AutoGLM 的 apps.py 翻译而来，包含 130+ 常见中文应用的包名映射。
 * Phone Agent 在执行 Launch 动作时，通过应用名查找包名来启动应用。
 *
 * 用法：AppPackages.getPackageName("微信") → "com.tencent.mm"
 */
object AppPackages {

    /**
     * 应用名称到包名的映射字典。
     * 包含中文名、英文名、别名等多种写法，方便模型匹配。
     */
    private val packages: Map<String, String> = mapOf(
        // ── 社交通讯 ──────────────────────────────────────────
        "微信" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "微博" to "com.sina.weibo",
        "weibo" to "com.sina.weibo",
        "飞书" to "com.ss.android.lark",
        "企业微信" to "com.tencent.wework",
        "钉钉" to "com.alibaba.android.rimet",
        "Telegram" to "org.telegram.messenger",
        "WhatsApp" to "com.whatsapp",

        // ── 电商购物 ──────────────────────────────────────────
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "唯品会" to "com.achievo.vipshop",
        "得物" to "com.shizhuang.duapp",
        "闲鱼" to "com.taobao.idlefish",
        "Temu" to "com.zzkko",
        "小红书" to "com.xingin.xhs",
        "xhs" to "com.xingin.xhs",

        // ── 美食外卖 ──────────────────────────────────────────
        "美团" to "com.sankuai.meituan",
        "美团外卖" to "com.sankuai.meituan.takeoutnew",
        "饿了么" to "me.ele",
        "大众点评" to "com.dianping.v1",
        "肯德基" to "com.yek.android.kfc.activitys",
        "麦当劳" to "com.mcdonalds.app",
        "海底捞" to "com.haidilao",

        // ── 出行旅游 ──────────────────────────────────────────
        "携程" to "ctrip.android.view",
        "Ctrip" to "ctrip.android.view",
        "12306" to "com.MobileTicket",
        "滴滴出行" to "com.sdu.didi.psnger",
        "滴滴" to "com.sdu.didi.psnger",
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "同程旅行" to "com.tongcheng.android",
        "去哪儿" to "com.Qunar",

        // ── 视频娱乐 ──────────────────────────────────────────
        "bilibili" to "tv.danmaku.bili",
        "B站" to "tv.danmaku.bili",
        "哔哩哔哩" to "tv.danmaku.bili",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "腾讯视频" to "com.tencent.qqlive",
        "爱奇艺" to "com.qiyi.video",
        "优酷" to "com.youku.phone",
        "芒果TV" to "com.hunant.tvimgo.interact",
        "YouTube" to "com.google.android.youtube",

        // ── 音乐音频 ──────────────────────────────────────────
        "网易云音乐" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
        "酷狗音乐" to "com.kugou.android",
        "酷我音乐" to "cn.kuwo.player",
        "汽水音乐" to "com.smile.gifmaker.music",
        "喜马拉雅" to "com.ximalaya.ting.android",

        // ── 生活服务 ──────────────────────────────────────────
        "知乎" to "com.zhihu.android",
        "豆瓣" to "com.douban.frodo",
        "今日头条" to "com.ss.android.article.news",
        "58同城" to "com.wuba",
        "中国移动" to "com.greenpoint.android.mobile",

        // ── 办公工具 ──────────────────────────────────────────
        "WPS" to "cn.wps.moffice_eng",
        "钉钉" to "com.alibaba.android.rimet",
        "百度网盘" to "com.baidu.netdisk",
        "腾讯文档" to "com.tencent.wework",

        // ── 浏览器 ──────────────────────────────────────────
        "Chrome" to "com.android.chrome",
        "浏览器" to "com.android.browser",
        "UC浏览器" to "com.UCMobile",
        "百度" to "com.baidu.searchbox",

        // ── 阅读小说 ──────────────────────────────────────────
        "番茄小说" to "com.dragon.read",
        "七猫免费小说" to "com.kmxs.reader",
        "起点读书" to "com.qidian.QDReader",

        // ── 游戏 ──────────────────────────────────────────
        "原神" to "com.miHoYo.Yuanshen",
        "崩坏星穹铁道" to "com.miHoYo.hkrpg",
        "星穹铁道" to "com.miHoYo.hkrpg",
        "恋与深空" to "com.papegames.lysk.cn",
        "王者荣耀" to "com.tencent.tmgp.sgame",

        // ── AI 与工具 ──────────────────────────────────────────
        "豆包" to "com.larus.nova",
        "美图秀秀" to "com.mt.mtxx.mtxx",
        "扫描全能王" to "com.intsig.camscanner",

        // ── 国际应用 ──────────────────────────────────────────
        "Google Maps" to "com.google.android.apps.maps",
        "Gmail" to "com.google.android.gm",
        "Twitter" to "com.twitter.android",
        "X" to "com.twitter.android",
        "Reddit" to "com.reddit.frontpage",
        "TikTok" to "com.zhiliaoapp.musically",
        "Duolingo" to "com.duolingo",
        "VLC" to "org.videolan.vlc",
        "Booking" to "com.booking",
        "Spotify" to "com.spotify.music",
        "Netflix" to "com.netflix.mediaclient",
        "Instagram" to "com.instagram.android",
        "Facebook" to "com.facebook.katana",
    )

    /**
     * 根据应用名称查找包名。
     *
     * 支持精确匹配和包含匹配（如 "微信" 和 "微信app" 都能匹配）。
     *
     * @param appName 应用名称（中文名或英文名）
     * @return 包名字符串，未找到返回 null
     */
    fun getPackageName(appName: String): String? {
        // 精确匹配优先
        packages[appName]?.let { return it }

        // 包含匹配：遍历所有 key，找到包含关系的
        for ((name, pkg) in packages) {
            if (appName.contains(name, ignoreCase = true) || name.contains(appName, ignoreCase = true)) {
                return pkg
            }
        }

        return null
    }

    /**
     * 根据包名反查应用名称（用于在状态消息中显示）。
     *
     * @param packageName 包名
     * @return 应用名称，未找到返回包名本身
     */
    fun getAppName(packageName: String): String {
        for ((name, pkg) in packages) {
            if (pkg == packageName) return name
        }
        return packageName
    }
}
