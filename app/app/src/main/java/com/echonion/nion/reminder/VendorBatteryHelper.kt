package com.echonion.nion.reminder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 厂商电池管理设置跳转工具类。
 *
 * 国产厂商（vivo / 小米 / 华为 / OPPO 等）在 Android 标准电池优化之外，
 * 自行实现了一套后台耗电管控机制。即使 App 加入了 Android 标准电池优化白名单，
 * 厂商自有层仍可能杀后台、丢弃 AlarmManager 闹钟。
 *
 * 本工具类根据 Build.MANUFACTURER 检测厂商，按优先级尝试跳转到厂商专属的
 * 后台管理设置页。所有厂商 Intent 均用 try-catch 保护，单个失败后自动 fallback
 * 到下一个选项，最终兜底到 Android 标准电池优化弹窗或应用详情页。
 */
object VendorBatteryHelper {

    private const val TAG = "VendorBatteryHelper"

    /**
     * 打开当前设备最适合的电池 / 后台管理设置页。
     *
     * 优先级链：
     * 1. 厂商专属后台管理 Activity（按优先级逐个尝试）
     * 2. Android 标准电池优化弹窗（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）
     * 3. 应用详情页（ACTION_APPLICATION_DETAILS_SETTINGS，极端兜底）
     *
     * @param context 上下文，用于 startActivity 和 PackageManager 查询
     */
    fun openBatterySettings(context: Context) {
        // 第 1 层：尝试厂商专属 Intent
        val vendorIntents = getVendorIntents()
        for (intent in vendorIntents) {
            if (tryStartActivity(context, intent)) {
                Log.d(TAG, "已跳转厂商电池设置页: ${intent.component}")
                return
            }
        }

        // 第 2 层：Android 标准电池优化弹窗
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val standardIntent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
            if (tryStartActivity(context, standardIntent)) {
                Log.d(TAG, "已跳转 Android 标准电池优化弹窗")
                return
            }
        }

        // 第 3 层：应用详情页（所有设备都能打开）
        val detailIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        if (tryStartActivity(context, detailIntent)) {
            Log.d(TAG, "已跳转应用详情页（兜底）")
            return
        }

        Log.w(TAG, "所有跳转方式均失败")
    }

    /**
     * 根据设备厂商返回专属的后台管理 Intent 列表（按优先级排序）。
     *
     * 每个厂商有多个候选 Activity，因为不同机型 / 系统版本可能使用不同的包名。
     * 前面的优先级最高，后面的作为同厂商的 fallback。
     */
    private fun getVendorIntents(): List<Intent> {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
                // vivo 后台高耗电管理页面（最直接）
                intent("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                // iQOO 系列白名单管理
                intent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                // vivo 自启动管理
                intent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            )

            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> listOf(
                // 小米安全中心 - 自启动管理
                intent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )

            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                // 华为手机管家 - 启动管理（新版）
                intent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                // 华为手机管家 - 启动管理（旧版）
                intent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity"),
                // 华为手机管家 - 受保护应用
                intent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )

            manufacturer.contains("oppo") || manufacturer.contains("realme") -> listOf(
                // OPPO 手机管家 - 自启动管理（新版）
                intent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                // OPPO 手机管家 - 自启动管理（旧版）
                intent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                // OPPO 安全中心（更旧版本）
                intent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )

            manufacturer.contains("samsung") -> listOf(
                // 三星电池管理
                intent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                intent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            )

            manufacturer.contains("oneplus") -> listOf(
                // 一加自启动管理
                intent("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            )

            manufacturer.contains("meizu") -> listOf(
                // 魅族后台管理
                intent("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
            )

            manufacturer.contains("asus") -> listOf(
                // 华硕自启动管理
                intent("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
            )

            else -> emptyList()
        }
    }

    /**
     * 构建一个 ComponentName 的 Intent。
     */
    private fun intent(pkg: String, cls: String): Intent {
        return Intent().setComponent(ComponentName(pkg, cls))
    }

    /**
     * 安全启动 Activity，先通过 PackageManager 检查目标是否存在。
     *
     * @return true 表示成功跳转，false 表示目标 Activity 不存在或启动失败
     */
    private fun tryStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            val resolved = context.packageManager.resolveActivity(
                intent,
                0,
            )
            if (resolved != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "跳转失败: ${intent.component}, 原因: ${e.message}")
            false
        }
    }
}
