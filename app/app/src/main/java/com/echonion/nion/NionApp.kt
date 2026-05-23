package com.echonion.nion

import android.app.Application
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import uniffi.nion_core.NionCore

/**
 * 数据变更事件 —— 当 Agent 工具或 UI 操作修改了底层数据时发出。
 *
 * TaskViewModel 等观察者监听此事件，自动刷新 UI，实现跨组件实时同步。
 *
 * @property type 变更类型："tasks" 或 "checklists"
 */
data class DataChangeEvent(val type: String)

class NionApp : Application() {
    lateinit var core: NionCore
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
     * 发出数据变更通知。
     *
     * @param type "tasks" 或 "checklists"
     */
    fun notifyDataChanged(type: String) {
        _dataEvents.tryEmit(DataChangeEvent(type))
    }

    override fun onCreate() {
        super.onCreate()
        val dbPath = getDir("nion_data", MODE_PRIVATE).absolutePath + "/nion.db"
        Log.d("NionApp", "Database path: $dbPath")
        try {
            core = NionCore(dbPath)
            Log.d("NionApp", "NionCore initialized successfully")
        } catch (e: Exception) {
            Log.e("NionApp", "Failed to initialize NionCore", e)
        }
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
fun Application.notifyDataChanged(type: String) = (this as NionApp).notifyDataChanged(type)
