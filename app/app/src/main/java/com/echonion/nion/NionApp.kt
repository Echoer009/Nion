package com.echonion.nion

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.io.File
import com.echonion.nion.reminder.GreetingScheduler
import com.echonion.nion.reminder.NotificationHelper
import com.echonion.nion.reminder.GreetingEvent
// import com.echonion.nion.reminder.PhoneAutomationScheduler  // Phone Agent 功能暂未成熟，注释禁用
import com.echonion.nion.reminder.ReminderEvent
import com.echonion.nion.reminder.ReminderScheduler
import com.echonion.nion.reminder.WeatherAlertEvent
import com.echonion.nion.reminder.WeatherAlertScheduler
import com.echonion.nion.ui.companion.tools.DataType
import com.echonion.nion.ui.focus.CompletionEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import uniffi.nion_core.NionCore

/**
 * 数据变更事件 —— 当 Agent 工具或 UI 操作修改了底层数据时发出。
 *
 * TaskViewModel 等观察者监听此事件，自动刷新 UI，实现跨组件实时同步。
 * 订阅者通常配合 debounce 使用，将短时间内多次事件合并为一次刷新。
 *
 * @property types 受影响的数据类别集合，来自工具的 [DataType] 声明
 */
data class DataChangeEvent(val types: Set<DataType>)

class NionApp : Application() {

    lateinit var core: NionCore
        private set

    /**
     * App 前后台状态标志。
     * 通过 ActivityLifecycleCallbacks 在 onActivityResumed/onActivityPaused 中维护。
     * 用于 ReminderWorker / GreetingWorker 判断是否需要发送 SharedFlow 事件给 UI 层：
     * - 前台时发事件 → ReminderOverlay / GreetingOverlay 弹 app 内弹窗 + dismiss 系统通知
     * - 后台时不发事件 → 走悬浮窗 Service 或系统通知
     */
    var isInForeground: Boolean = false
        private set

    /**
     * 数据变更事件总线。
     *
     * 任何组件修改了任务或清单数据后，调用 [notifyDataChanged] 发出事件。
     * 其他组件通过 [dataEvents] 收集事件并刷新 UI。
     * 使用 SharedFlow 确保多个订阅者都能收到事件，且不会因订阅者慢而阻塞发送者。
     */
    private val _dataEvents = MutableSharedFlow<DataChangeEvent>(extraBufferCapacity = 8)
    val dataEvents: SharedFlow<DataChangeEvent> = _dataEvents.asSharedFlow()

    /**
     * 提醒事件总线 —— ReminderWorker 触发时通过此总线通知 UI 层。
     *
     * ReminderOverlay 监听此事件流，收到事件后弹出全局提醒弹窗。
     * 使用 SharedFlow 确保即使 UI 还没准备好也不会丢失事件。
     */
    private val _reminderEvents = MutableSharedFlow<ReminderEvent>(extraBufferCapacity = 4)
    val reminderEvents: SharedFlow<ReminderEvent> = _reminderEvents.asSharedFlow()

    /**
     * 问候事件总线 —— GreetingWorker 触发时通过此总线通知 UI 层。
     *
     * GreetingOverlay 监听此事件流，收到事件后弹出问候悬浮卡片。
     */
    private val _greetingEvents = MutableSharedFlow<GreetingEvent>(extraBufferCapacity = 4)
    val greetingEvents: SharedFlow<GreetingEvent> = _greetingEvents.asSharedFlow()

    /**
     * 天气预警事件总线 —— WeatherAlertWorker 触发时通过此总线通知 UI 层。
     *
     * WeatherAlertOverlay 监听此事件流，收到事件后弹出天气预警悬浮卡片。
     */
    private val _weatherAlertEvents = MutableSharedFlow<WeatherAlertEvent>(extraBufferCapacity = 4)
    val weatherAlertEvents: SharedFlow<WeatherAlertEvent> = _weatherAlertEvents.asSharedFlow()

    /**
     * 专注完成事件总线 —— 专注计时完成/中断时通过此总线通知 UI 层。
     *
     * CompletionOverlay 监听此事件流，收到事件后弹出鼓励悬浮卡片。
     */
    private val _completionEvents = MutableSharedFlow<CompletionEvent>(extraBufferCapacity = 4)
    val completionEvents: SharedFlow<CompletionEvent> = _completionEvents.asSharedFlow()

    /**
     * 发送提醒事件到 UI 层。
     * 由 ReminderWorker 在提醒逻辑执行时调用。
     */
    fun postReminderEvent(event: ReminderEvent) {
        _reminderEvents.tryEmit(event)
    }

    /**
     * 发送问候事件到 UI 层。
     * 由 GreetingWorker 在前台模式下调用。
     */
    fun postGreetingEvent(event: GreetingEvent) {
        _greetingEvents.tryEmit(event)
    }

    /**
     * 发送天气预警事件到 UI 层。
     * 由 WeatherAlertWorker 在前台模式下调用。
     */
    fun postWeatherAlertEvent(event: WeatherAlertEvent) {
        _weatherAlertEvents.tryEmit(event)
    }

    /**
     * 发送专注完成事件到 UI 层。
     * 由 FocusTimerViewModel 在专注完成/中断时调用。
     */
    fun postCompletionEvent(event: CompletionEvent) {
        _completionEvents.tryEmit(event)
    }

    /**
     * 发出数据变更通知。
     *
     * @param types 受影响的数据类别集合
     */
    fun notifyDataChanged(types: Set<DataType>) {
        _dataEvents.tryEmit(DataChangeEvent(types))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 数据库放内部存储（databases/ 目录）。历史版本曾放 getExternalFilesDir
        // （FUSE 外置存储），SQLite 在其上写入不可靠，且 adb 恢复的文件属主是
        // shell(2000)，App 无写权限 → "attempt to write a readonly database" 崩溃。
        // prepareDatabasePath() 内含一次性迁移：把旧外置库原样搬进来，数据不丢。
        val dbPath = prepareDatabasePath()
        Log.d("NionApp", "Database path: $dbPath")
        try {
            core = NionCore(dbPath)
            Log.d("NionApp", "NionCore initialized successfully")
        } catch (e: Exception) {
            Log.e("NionApp", "Failed to initialize NionCore", e)
        }

        // 初始化音乐播放门面 —— 建立 Agent 工具（MusicTool）专用的第二条 MediaController
        // 连接（与音乐页 MusicViewModel 各自独立，进程内多客户端共用同一播放器实例）。
        // 必须放在 NionCore 初始化之后：工具执行时凭据/音源均从 core settings 读取。
        try {
            com.echonion.nion.ui.music.MusicControllerFacade.init(this)
        } catch (e: Exception) {
            Log.e("NionApp", "初始化音乐播放门面失败", e)
        }

        // 创建通知渠道（重复调用安全，系统会忽略已存在的渠道）
        NotificationHelper.createChannel(this)

        // 重调度所有提醒闹钟（App 启动时确保闹钟状态一致）
        try {
            ReminderScheduler.rescheduleAll(this, core)
        } catch (e: Exception) {
            Log.e("NionApp", "重调度提醒闹钟失败", e)
        }

        // 重调度情景问候闹钟
        try {
            GreetingScheduler.rescheduleAll(this, core)
        } catch (e: Exception) {
            Log.e("NionApp", "重调度问候闹钟失败", e)
        }

        // 启动天气预警定时检查（每小时检查一次天气）
        try {
            val weatherEnabled = core.getSetting("weather_alert_enabled")
            if (weatherEnabled != "false") {
                WeatherAlertScheduler.start(this)
            }
        } catch (e: Exception) {
            Log.e("NionApp", "启动天气预警调度失败", e)
        }
        // 重调度定时手机自动化任务闹钟 —— Phone Agent 功能暂未成熟，注释禁用
        // try {
        //     PhoneAutomationScheduler.rescheduleAll(this, core)
        // } catch (e: Exception) {
        //     Log.e("NionApp", "重调度定时手机任务闹钟失败", e)
        // }

        // 注册 Activity 生命周期回调，跟踪 app 前后台状态
        // 供 ReminderWorker 判断是否发送 SharedFlow 事件给 UI 层
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                isInForeground = true
            }
            override fun onActivityPaused(activity: Activity) {
                isInForeground = false
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityDestroyed(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    /**
     * 计算数据库路径，并在首次运行时把旧外置存储的库迁移到内部存储。
     *
     * 迁移规则：内部库不存在 && 旧外置库存在 → 整文件拷贝到内部 databases/ 目录，
     * 成功后将旧库改名 nion.db.migrated 留档（不再使用），失败则回退用全新内部库。
     *
     * @return 内部存储数据库文件绝对路径
     */
    private fun prepareDatabasePath(): String {
        val target = getDatabasePath("nion.db") // → /data/user/0/<包名>/databases/nion.db
        target.parentFile?.mkdirs()
        val legacy = getExternalFilesDir(null)?.resolve("nion.db")
        if (!target.exists() && legacy != null && legacy.exists()) {
            try {
                legacy.inputStream().use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                val archive = File(legacy.parentFile, "nion.db.migrated")
                if (!legacy.renameTo(archive)) legacy.delete() // 改名失败就删除，防止重复迁移
                Log.i("NionApp", "数据库已从外置存储迁移到内部存储: ${target.absolutePath}")
            } catch (e: Exception) {
                Log.e("NionApp", "数据库迁移失败，使用内部存储新库", e)
            }
        }
        return target.absolutePath
    }


    companion object {
        /** 全局 Application 实例引用，供 QueryTool 天气查询等需要 Context 的组件使用 */
        var instance: NionApp? = null
            private set
    }
}

fun Application.core(): NionCore = (this as NionApp).core

/**
 * 获取数据变更事件总线，供 ViewModel 监听。
 */
fun Application.dataEvents(): SharedFlow<DataChangeEvent> = (this as NionApp).dataEvents

/**
 * 发出数据变更通知，供工具执行器等调用。
 */
fun Application.notifyDataChanged(types: Set<DataType>) = (this as NionApp).notifyDataChanged(types)
