package com.echonion.nion.ui.task

/**
 * 任务列表操作辅助函数集合。
 * 包含列表增删改、递归遍历、扁平化等功能，供 TaskViewModel 和 FocusSetupViewModel 共用。
 */

/**
 * 在任务树中递归替换指定 ID 的任务。
 * 用于乐观更新：先在 UI 层修改本地状态，后台再同步到数据库。
 *
 * @param tasks 当前任务列表
 * @param targetId 需要替换的任务 ID
 * @param updated 替换后的新任务对象
 * @return 替换后的新任务列表（不可变）
 */
internal fun updateTaskInList(tasks: List<TaskItem>, targetId: String, updated: TaskItem): List<TaskItem> {
    return tasks.map { task ->
        if (task.id == targetId) updated
        else task.copy(subtasks = updateTaskInList(task.subtasks, targetId, updated))
    }
}

/**
 * 递归标记任务及其所有子任务为已完成。
 * 用于级联完成：父任务完成时，所有子任务一并标记为 done。
 *
 * @param task 需要标记的任务
 * @return 标记后的新任务对象（isDone = true）
 */
internal fun markAllDone(task: TaskItem): TaskItem {
    return task.copy(isDone = true, subtasks = task.subtasks.map { markAllDone(it) })
}

/**
 * 递归标记任务及其所有子任务为未完成。
 * 用于级联取消完成：父任务取消完成时，所有子任务一并恢复为 todo。
 *
 * @param task 需要标记的任务
 * @return 标记后的新任务对象（isDone = false）
 */
internal fun markAllTodo(task: TaskItem): TaskItem {
    return task.copy(isDone = false, subtasks = task.subtasks.map { markAllTodo(it) })
}

/**
 * 递归收集任务树中所有任务的 ID（含自身和全部子孙）。
 * 用于批量更新状态：将收集到的 ID 列表逐一调用 core.updateTask。
 *
 * @param task 任务树的根节点
 * @return 所有任务 ID 的扁平列表
 */
internal fun collectIds(task: TaskItem): List<String> {
    return listOf(task.id) + task.subtasks.flatMap { collectIds(it) }
}

/**
 * 从任务树中递归移除指定 ID 的任务（含子树）。
 * 用于乐观删除：先从 UI 列表移除，后台再执行数据库删除。
 *
 * @param tasks 当前任务列表
 * @param targetId 需要移除的任务 ID
 * @return 移除后的新任务列表
 */
internal fun removeTaskFromList(tasks: List<TaskItem>, targetId: String): List<TaskItem> {
    return tasks.filter { it.id != targetId }
        .map { it.copy(subtasks = removeTaskFromList(it.subtasks, targetId)) }
}

/**
 * 递归遍历任务树，将已完成任务按母子分组添加到结果列表。
 * 已完成的任务作为组根，其已完成的子任务跟随其后、缩进显示。
 * 用于构建 flatDoneTasks：渲染已完成区域的连接卡片样式。
 *
 * @param tasks 任务列表
 * @param baseDepth 当前层级深度（根节点从 0 开始）
 * @param result 累积的 FlatTaskItem 列表
 */
internal fun addDoneGroups(
    tasks: List<TaskItem>,
    baseDepth: Int,
    result: MutableList<FlatTaskItem>,
) {
    for (task in tasks) {
        if (task.isDone) {
            val subs = mutableListOf<FlatTaskItem>()
            collectDoneSubs(task.subtasks, depth = baseDepth + 1, parentId = task.id, subs)
            val hasDoneSubs = subs.isNotEmpty()
            result.add(FlatTaskItem(
                task = task,
                depth = baseDepth,
                parentId = null,
                isGroupFirst = true,
                isGroupLast = !hasDoneSubs,
            ))
            if (hasDoneSubs) {
                subs[subs.lastIndex] = subs.last().copy(isGroupLast = true)
                result.addAll(subs)
            }
        } else {
            addDoneGroups(task.subtasks, baseDepth, result)
        }
    }
}

/**
 * 收集已完成父任务下所有已完成的子孙任务，跳过未完成的。
 * 只收集 isDone=true 的项，保持相对深度。
 * 供 addDoneGroups 内部调用。
 *
 * @param subs 子任务列表
 * @param depth 当前深度
 * @param parentId 父任务 ID
 * @param result 累积的 FlatTaskItem 列表
 */
internal fun collectDoneSubs(
    subs: List<TaskItem>,
    depth: Int,
    parentId: String,
    result: MutableList<FlatTaskItem>,
) {
    for ((index, sub) in subs.withIndex()) {
        if (!sub.isDone) {
            collectDoneSubs(sub.subtasks, depth + 1, sub.id, result)
            continue
        }
        val hasDoneSubs = sub.subtasks.any { it.isDone }
        val isLast = index == subs.lastIndex || subs.drop(index + 1).all { !it.isDone }
        result.add(FlatTaskItem(
            task = sub,
            depth = depth,
            parentId = parentId,
            isGroupFirst = false,
            isGroupLast = isLast && !hasDoneSubs,
        ))
        collectDoneSubs(sub.subtasks, depth + 1, sub.id, result)
    }
}

/** 将任务树展平为 FlatTaskItem 列表，供 TaskViewModel 和 FocusSetupViewModel 共用 */
internal fun flattenWithGroupInfo(tasks: List<TaskItem>): List<FlatTaskItem> {
    val result = mutableListOf<FlatTaskItem>()
    for (task in tasks) {
        flattenTodoGroup(task, depth = 0, result)
    }
    return result
}

/**
 * 递归展开待办任务组。未完成任务作为组根 + 递归子树；
 * 已完成任务跳过自身但继续深入子树（子任务可能被单独取消完成）。
 * 供 flattenWithGroupInfo 内部调用。
 */
internal fun flattenTodoGroup(
    task: TaskItem,
    depth: Int,
    result: MutableList<FlatTaskItem>,
) {
    if (!task.isDone) {
        val subItems = mutableListOf<FlatTaskItem>()
        flattenSubs(task.subtasks, depth = depth + 1, parentId = task.id, subItems)
        val hasSubs = subItems.isNotEmpty()
        result.add(FlatTaskItem(
            task = task,
            depth = depth,
            parentId = null,
            isGroupFirst = true,
            isGroupLast = !hasSubs,
        ))
        if (hasSubs) {
            subItems[subItems.lastIndex] = subItems.last().copy(isGroupLast = true)
            result.addAll(subItems)
        }
    } else {
        for (sub in task.subtasks) {
            flattenTodoGroup(sub, depth, result)
        }
    }
}

/** 递归展开子任务为 FlatTaskItem 列表，跳过已完成项但继续递归其子树。供 flattenTodoGroup 内部调用。 */
internal fun flattenSubs(
    subs: List<TaskItem>,
    depth: Int,
    parentId: String,
    result: MutableList<FlatTaskItem>,
) {
    for ((index, sub) in subs.withIndex()) {
        if (sub.isDone) {
            flattenSubs(sub.subtasks, depth + 1, sub.id, result)
            continue
        }
        val hasChildSubs = sub.subtasks.any { !it.isDone }
        val isLastInSameParent = index == subs.lastIndex || subs.drop(index + 1).all { it.isDone }
        result.add(FlatTaskItem(
            task = sub,
            depth = depth,
            parentId = parentId,
            isGroupFirst = false,
            isGroupLast = isLastInSameParent && !hasChildSubs,
        ))
        flattenSubs(sub.subtasks, depth + 1, sub.id, result)
    }
}
