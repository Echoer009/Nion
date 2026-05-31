package com.echonion.nion.ui.task

import uniffi.nion_core.AttachmentData
import uniffi.nion_core.ChecklistData
import uniffi.nion_core.DailyTaskStatus
import uniffi.nion_core.GroupData
import uniffi.nion_core.TaskData

/**
 * 将 Rust 端 DailyTaskStatus 转换为 UI 模型。
 * 新模型：所有任务（含每日任务）的完成状态统一由 completedForDate 决定，
 * completedForDate 来自 tasks.status == "done"（Rust 端已处理）。
 */
internal fun DailyTaskStatus.toUi(): TaskItem {
    val isDaily = task.recurrenceRule == "daily"
    return TaskItem(
        id = task.id,
        name = task.name,
        description = task.description,
        priority = task.priority,
        isDone = completedForDate,
        createdAt = task.createdAt,
        focusSeconds = task.focusSeconds,
        recurrenceRule = task.recurrenceRule,
        recurrenceReminderTime = task.recurrenceReminderTime,
        reminder = task.reminder,
        isDaily = isDaily,
        isCompletedForDate = completedForDate,
    )
}

/**
 * 将 Rust 端 TaskData 转换为 UI 模型（用于清单视图）。
 * 新模型：每日任务也是独立实例，完成状态由 tasks.status 决定（和普通任务一致）。
 * 供 TaskViewModel 和 FocusSetupViewModel 共用。
 */
internal fun TaskData.toUi(): TaskItem {
    val isDaily = recurrenceRule == "daily"
    return TaskItem(
        id = id,
        name = name,
        description = description,
        priority = priority,
        isDone = status == "done",
        createdAt = createdAt,
        focusSeconds = focusSeconds,
        recurrenceRule = recurrenceRule,
        recurrenceReminderTime = recurrenceReminderTime,
        reminder = reminder,
        isDaily = isDaily,
        isCompletedForDate = isDaily && status == "done",
    )
}

/** 将 Rust 端 ChecklistData 转换为 UI 模型（清单列表项） */
internal fun ChecklistData.toUi(): ChecklistItem = ChecklistItem(
    id = id,
    name = name,
)

/** 将 Rust 端 GroupData 转换为 UI 模型（分组列表项） */
internal fun GroupData.toUi(): GroupItem = GroupItem(
    id = id,
    name = name,
    checklistId = checklistId,
    color = color,
)

/** 将 Rust 端 AttachmentData 转换为 UI 模型（附件列表项） */
internal fun AttachmentData.toUi(): AttachmentUiItem = AttachmentUiItem(
    id = id,
    fileName = fileName,
    filePath = filePath,
    mimeType = mimeType,
    fileSize = fileSize,
    isImage = mimeType.startsWith("image/"),
)
