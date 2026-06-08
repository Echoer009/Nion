# Web Search MVP 实现计划 — DDG HTML 抓取方案

## 概述

为 AI companion 添加互联网搜索能力。MVP 使用 DuckDuckGo HTML 端点抓取搜索结果，注册到现有 Tool 系统。用户无需任何配置即可使用。

## 方案选型背景

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| DDG HTML 抓取 | 免费、无限、无需 API Key、全球统一入口 | 有反爬风险、结果质量中等 | **MVP 采用** |
| Bing HTML 抓取 | 免费、Chatbox 验证过 | 国内 `bing.com` 重定向到 `cn.bing.com`，HTML 结构不同；安卓端不可靠（Chatbox issue #2509） | 备选 fallback |
| Google 抓取 | 质量最高 | 2025 起必须 JS 渲染，几乎不可能抓取；有法律风险 | 不可行 |
| Tavily/Brave | 质量高、稳定 | 需 API Key、有额度限制 | 未来可选配置 |
| 自建 SearXNG | 最可控 | 需服务器、成本、维护 | 长期方案 |

## 架构设计

### 搜索流程

```
用户提问
  → LLM 判断需要搜索（tool calling）
    → 调用 websearch tool
      → POST https://html.duckduckgo.com/html/（伪装 UA）
      → Jsoup 解析 HTML
      → 提取 title / url / snippet（最多 10 条）
    → 搜索结果 JSON 回传 LLM
  → LLM 基于搜索结果回答用户
```

### DDG HTML 端点

- **URL**: `https://html.duckduckgo.com/html/`
- **方法**: POST（表单提交，`q=搜索词`）
- **不需要 JavaScript**: 纯 HTML 响应，Android 端 OkHttp 直接请求
- **不需要 API Key**
- **无硬性月度额度限制**（有 IP 速率限制，用户各自用手机网络，压力分散）

### HTML 解析选择器

参考 SearXNG 源码（`searx/engines/duckduckgo.py`）中的 DDG HTML 解析逻辑：

| 数据 | 选择器 | 说明 |
|------|--------|------|
| 结果容器 | `div#links > div.web-result` 或 `div.result` | 每个搜索结果 |
| 标题 | `a.result__a` | text 为标题，href 为链接 |
| 摘要 | `a.result__snippet` | text 为摘要文字 |
| URL 清洗 | 去除 DDG 重定向前缀 `//duckduckgo.com/l/?uddg=` | URL 编码的 `uddg` 参数为真实 URL |

## 需要修改/创建的文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `app/app/build.gradle.kts` | 修改 | 添加 Jsoup 依赖 |
| `app/.../companion/tools/WebSearchService.kt` | **新建** | DDG HTTP 请求 + HTML 解析 |
| `app/.../companion/tools/WebSearchTool.kt` | **新建** | Tool 接口实现 |
| `app/.../companion/tools/ToolRegistry.kt` | 修改 | 注册 WebSearchTool |
| `app/.../companion/CompanionViewModel.kt` | 修改 | 添加 toolDisplayName / toolResultText |
| `app/.../companion/tools/ToolPhrasesFemale.kt` | 修改 | 添加搜索相关话术 |
| `app/.../companion/tools/ToolPhrasesMale.kt` | 修改 | 添加搜索相关话术 |

## 详细步骤

### Step 1: 添加 Jsoup 依赖

**文件**: `app/app/build.gradle.kts`

```kotlin
// Jsoup —— HTML 解析，用于 DDG 搜索结果抓取
implementation("org.jsoup:jsoup:1.18.3")
```

Jsoup 是成熟的 Java HTML 解析库（~400KB），提供 jQuery 风格的 CSS 选择器 API，适合抓取 DDG 静态 HTML 页面。

### Step 2: 创建 WebSearchService.kt

**文件**: `app/app/src/main/java/com/echonion/nion/ui/companion/tools/WebSearchService.kt`

独立的搜索服务，职责：
- HTTP 请求 DDG HTML 端点
- HTML 解析提取搜索结果
- 结果格式化为 JSON
- 错误处理

```kotlin
object WebSearchService {
    // OkHttp 客户端，10 秒超时（复用 WeatherService 的模式）
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // DDG HTML 端点
    private const val DDG_URL = "https://html.duckduckgo.com/html/"

    // 伪装 Chrome User-Agent
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ..."

    suspend fun search(query: String): String {
        // 1. POST 请求 DDG
        // 2. Jsoup 解析 HTML
        // 3. 提取结果（最多 10 条）
        // 4. 返回 JSON
    }
}
```

**返回 JSON 格式**：
```json
{
  "results": [
    {
      "title": "页面标题",
      "url": "https://example.com/page",
      "snippet": "搜索结果摘要..."
    }
  ],
  "count": 5,
  "query": "原始搜索词"
}
```

**错误时返回**：
```json
{
  "error": "搜索失败: 网络超时",
  "results": [],
  "count": 0,
  "query": "原始搜索词"
}
```

### Step 3: 创建 WebSearchTool.kt

**文件**: `app/app/src/main/java/com/echonion/nion/ui/companion/tools/WebSearchTool.kt`

```kotlin
object WebSearchTool : Tool {
    override val name = "websearch"
    override val description = "搜索互联网获取最新信息。当用户询问实时事件、新闻、天气以外的外部知识时使用。返回搜索结果的标题、链接和摘要。"
    override val affectsData = emptySet<DataType>()  // 只读工具

    override fun parametersSchema(): JSONObject {
        // JSON Schema: query (string, required)
    }

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val query = params.getString("query")
        return WebSearchService.search(query)
    }
}
```

### Step 4: 注册到 ToolRegistry

**文件**: `app/.../companion/tools/ToolRegistry.kt`

```kotlin
val baseTools: List<Tool> = listOf(
    QueryTool,
    CreateTool,
    UpdateTool,
    DeleteTool,
    ManageTool,
    MemoryTool,
    WebSearchTool,  // 新增：互联网搜索
)
```

WebSearchTool 加入 `baseTools`，所有页面都可用。

### Step 5: 添加 UI 话术

#### CompanionViewModel.kt

`toolDisplayName` 方法添加：
```kotlin
"websearch" -> "正在搜索..."
```

`toolResultText` 方法添加：
```kotlin
"websearch" -> {
    val count = resultJson.optJSONArray("results")?.length() ?: 0
    val subKey = if (count > 0) "search_results" else "search_empty"
    ToolPhrasePool.pick(companionStyle, subKey, mapOf("count" to count.toString()))
}
```

#### ToolPhrasesFemale.kt / ToolPhrasesMale.kt

每个风格添加 `search_results` 和 `search_empty` 子键，各 5 条候选文案。

示例（元气少女风格）：
```kotlin
"search_results" to listOf(
    "帮你查到了 {count} 条相关信息！",
    "搜索完毕~找到 {count} 条结果！",
    "锵锵~帮你搜到了 {count} 条有用的信息！",
    "查到啦！一共 {count} 条搜索结果~",
    "搜了一圈，找到 {count} 条相关信息~",
),
"search_empty" to listOf(
    "嗯...没有搜到相关信息呢",
    "抱歉，搜索结果为空...",
    "没找到相关的内容...",
    "搜索了一下，但似乎没有匹配的结果...",
    "查不到相关信息呢，换个关键词试试？",
),
```

### Step 6: 验证

```bash
# 构建
cd app && ./gradlew assembleStandardDebug

# 安装测试
./deploy.sh

# 测试用例
# 1. 在 companion 中问 "今天的新闻"
# 2. 问 "Rust 语言最新版本是什么"
# 3. 问 "不需要搜索的问题"（如 "你好"）→ 不应触发搜索
```

## 已知风险与缓解

| 风险 | 概率 | 缓解措施 |
|------|------|---------|
| DDG 速率限制/IP 封锁 | 低（用户各自 IP，频率低） | 返回友好错误信息，LLM 告知用户稍后再试 |
| DDG HTML 结构变化 | 中 | 封装解析逻辑到独立 Service，变化时只改一处 |
| DDG 返回 CAPTCHA | 低 | 用户场景偶尔搜索，很少触发 |
| 搜索结果质量不佳 | 中 | LLM 可以综合多条结果判断；后续可加 Bing fallback |
| 国内无法访问 DDG | 低 | `html.duckduckgo.com` 目前国内可直连 |

## MVP 范围外（后续迭代）

- [ ] Bing HTML 抓取 fallback（`cn.bing.com/search`）
- [ ] 搜索结果缓存（5 分钟 TTL）
- [ ] 设置页"自定义搜索 API"选项（Tavily/Brave/Bocha API Key）
- [ ] 系统提示中注入当前日期（帮助 LLM 判断是否需要搜索）
- [ ] 搜索开关（用户可关闭搜索功能）
- [ ] 搜索结果中的 URL 可点击跳转
- [ ] 用户可见的搜索结果引用卡片 UI
