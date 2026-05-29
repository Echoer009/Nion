package com.echonion.nion.ui.focus

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.util.Log
import androidx.activity.compose.BackHandler
import com.echonion.nion.core
import com.echonion.nion.dataEvents
import com.echonion.nion.ui.companion.tools.DataType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.echonion.nion.ui.task.ChecklistItem
import com.echonion.nion.ui.task.FlatTaskItem
import com.echonion.nion.ui.task.TaskItem
import com.echonion.nion.ui.task.flattenWithGroupInfo
import com.echonion.nion.ui.task.toUi
import uniffi.nion_core.NionCore
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 专注任务选择的 ViewModel —— 加载清单列表和未完成任务（含子任务层级），
 * 支持按清单筛选。
 *
 * 数据流：
 * 1. core.getChecklists() 获取所有清单，用于筛选 UI
 * 2. 根据 selectedChecklistId 决定查询方式：
 *    - null（全部）→ core.getTasks() 获取所有任务
 *    - 具体清单 ID → core.getTasksByCategory(id, null) 仅获取该清单根任务
 * 3. 按 parentId 构建父子树结构
 * 4. 复用 flattenWithGroupInfo() 展平为 FlatTaskItem 列表
 * 5. 展平过程自动跳过已完成任务，但保留其未完成子任务
 */
@OptIn(FlowPreview::class)
class FocusSetupViewModel(
    private val app: Application,
    private val core: NionCore,
) : ViewModel() {
    /** 展平后的待办任务列表（含层级深度信息），用于专注任务选择面板 */
    var flatTasks by mutableStateOf<List<FlatTaskItem>>(emptyList())
        private set

    /** 所有清单列表，用于筛选 UI 的 FilterChip 渲染 */
    var checklists by mutableStateOf<List<ChecklistItem>>(emptyList())
        private set

    /** 当前选中的清单 ID，null 表示显示全部清单的任务 */
    var selectedChecklistId by mutableStateOf<String?>(null)
        private set

    /** ViewModel 创建时加载任务数据，并订阅数据变更事件 */
    init {
        loadTasks()
        // 监听数据变更事件（AI 工具、TaskViewModel、ScheduleViewModel 等外部操作），自动刷新任务列表
        // debounce(300)：合并 AI 连续调用多个工具时的连续事件，只触发一次刷新
        viewModelScope.launch {
            app.dataEvents()
                .debounce(300)
                .collect { event ->
                    if (DataType.TASK_DATA in event.types) {
                        Log.d("FocusSetupViewModel", "收到数据变更事件: ${event.types}")
                        loadTasks()
                    }
                }
        }
    }

    /** 从数据库加载清单列表和任务列表 */
    fun loadTasks() {
        viewModelScope.launch {
            try {
                // 并行加载清单列表和任务列表
                val (loadedChecklists, loadedTasks) = withContext(Dispatchers.IO) {
                    val cls = core.getChecklists().map {
                        ChecklistItem(id = it.id, name = it.name)
                    }
                    val tasks = loadTasksForChecklist(selectedChecklistId)
                    Pair(cls, tasks)
                }
                checklists = loadedChecklists
                flatTasks = loadedTasks
            } catch (_: Exception) {}
        }
    }

    /**
     * 切换清单筛选，重新加载任务列表。
     * @param id 清单 ID，null 表示显示全部清单的任务
     */
    fun setChecklistFilter(id: String?) {
        selectedChecklistId = id
        loadTasks()
    }

    /**
     * 根据清单 ID 加载对应的任务树并展平。
     * checklistId = null 时加载全部任务，否则只加载该清单的根任务。
     */
    private fun loadTasksForChecklist(checklistId: String?): List<FlatTaskItem> {
        // 获取根任务列表：null 用 getTasks()（含子任务），具体清单用 getTasksByCategory（仅根任务）
        val rootTasks = if (checklistId == null) {
            core.getTasks()
        } else {
            core.getTasksByCategory(checklistId, null)
        }

        if (checklistId == null) {
            // 全部清单模式：getTasks() 返回所有任务（含子任务），从全量中构建父子树
            val childrenMap = rootTasks.groupBy { it.parentId }
            val roots = rootTasks.filter { it.parentId == null }
            fun buildTree(td: uniffi.nion_core.TaskData): TaskItem {
                val children = childrenMap[td.id] ?: emptyList()
                return td.toUi().copy(
                    subtasks = children.map { buildTree(it) }
                )
            }
            val tree = roots.map { buildTree(it) }
            return flattenWithGroupInfo(tree)
        } else {
            // 单清单模式：根任务从 getTasksByCategory 获取，子任务递归加载
            fun loadChildren(parentId: String): List<TaskItem> {
                return core.getSubtasks(parentId).map { task ->
                    task.toUi().copy(subtasks = loadChildren(task.id))
                }
            }
            val tree = rootTasks.map { root ->
                root.toUi().copy(subtasks = loadChildren(root.id))
            }
            return flattenWithGroupInfo(tree)
        }
    }
}

/**
 * 获取 Activity 作用域的 FocusTimerViewModel。
 *
 * 通过 viewModelStoreOwner = activity 将 ViewModel 绑定到 Activity 生命周期，
 * 使得即使用户在导航页之间切换（FocusScreen 被销毁/重建），
 * ViewModel 中的计时器状态和倒计时协程也不会中断。
 */
@Composable
private fun focusTimerViewModel(): FocusTimerViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    return viewModel(
        viewModelStoreOwner = context as ComponentActivity,
        factory = FocusTimerViewModel.Factory(app),
    )
}

@Composable
private fun focusSetupViewModel(): FocusSetupViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return FocusSetupViewModel(app, app.core()) as T
            }
        }
    )
}

/**
 * 专注计时器主界面。
 *
 * 交互设计：
 * - 正常态：300dp 圆形计时器，60 根圆角长条刻度（5 的倍数更长），中间显示时间
 * - 点击外圈刻度：直接设置专注时长（根据刻度位置计算分钟数）
 * - 点击中心区域：深橘色背景从时钟中心向外扩展为全屏，
 *   内部显示任务卡片列表（累计专注越久颜色越深），
 *   选中任务后背景从外围收缩回时钟中心
 *
 * 所有计时器状态（isRunning、remainingSeconds、focusMinutes 等）存放在
 * Activity 作用域的 FocusTimerViewModel 中，导航切换不会丢失。
 *
 * @param onOpenCompanion 点击右上角伙伴图标的回调
 * @param preselectedTaskId 从外部（如任务详情）跳转时传入的预选任务 ID
 * @param preselectedTaskTitle 预选任务的标题
 * @param preselectedDuration 从外部传入的预选专注时长（分钟），覆盖默认 25 分钟
 * @param autoStart 是否自动启动计时器（从任务详情跳转时为 true）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    onOpenCompanion: () -> Unit = {},
    preselectedTaskId: String? = null,
    preselectedTaskTitle: String? = null,
    preselectedDuration: Int? = null,
    autoStart: Boolean = false,
) {
    // Activity 作用域的 ViewModel，导航切换不丢失计时器状态
    val vm = focusTimerViewModel()

    // 处理外部传入的预选任务信息（ViewModel 内部通过 consumedPreselectedId 去重，仅应用一次）
    LaunchedEffect(preselectedTaskId) {
        vm.applyPreselection(preselectedTaskId, preselectedTaskTitle, preselectedDuration, autoStart)
    }

    // showTaskPanel: 是否显示任务选择面板（纯 UI 状态，留在 Composable 中）
    var showTaskPanel by remember { mutableStateOf(false) }

    // 任务选择面板返回拦截：面板打开时，系统返回手势关闭面板而非退出页面
    BackHandler(enabled = showTaskPanel) {
        showTaskPanel = false
    }

    val totalSeconds = vm.totalSeconds
    // progress: 剩余时间占总时间的比例，从 ViewModel 读取
    val progress = vm.progress

    // animatedProgress: 进度的动画值，平滑过渡
    val animatedProgress = remember { Animatable(1f) }
    // 根据 needsProgressSnap 决定是瞬间跳转还是动画过渡
    LaunchedEffect(progress, vm.needsProgressSnap) {
        if (vm.needsProgressSnap) {
            // reset / stopEarly / setDuration 后瞬间重置，不做动画
            animatedProgress.snapTo(progress)
            vm.clearProgressSnap()
        } else {
            // 正常倒计时：平滑过渡到新进度
            animatedProgress.animateTo(
                targetValue = progress,
                animationSpec = tween(
                    durationMillis = 600,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
            )
        }
    }

    val minutes = vm.remainingSeconds / 60
    val seconds = vm.remainingSeconds % 60
    val timeText = String.format("%02d:%02d", minutes, seconds)

    // ---- 高亮刻度状态 ----

    // highlightVisible: 高亮刻度是否可见（由触摸手势控制）
    // 手指按下后设 true，松手后延迟一小段时间再设 false（让用户看到点击反馈）
    var highlightVisible by remember { mutableStateOf(false) }

    // touchGeneration: 触摸代际计数器，防止快速连续触摸时旧的延迟协程错误关闭高亮
    var touchGeneration by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // isDragging: 是否正在拖拽刻度盘，控制高亮动画速度
    // 拖拽中用 snap() 瞬间跟随手指，松手后用 tween(400ms) 缓动到吸附位置
    var isDragging by remember { mutableStateOf(false) }

    // highlightFraction: 高亮刻度在圆周上的目标位置比例（0=12点, 1=回到12点）
    // 始终跟踪 focusMinutes，无论是否可见
    val highlightFraction = vm.focusMinutes.toFloat() / 120f

    // highlightPos: 高亮刻度的动画位置值
    // 拖拽时 snap() 瞬间跟随手指位置，松手后 tween(400ms) 缓动过渡到吸附位置
    // 不可见时动画也在后台运行，这样下次触摸时位置已经在最新值
    val highlightPos by animateFloatAsState(
        targetValue = highlightFraction,
        animationSpec = if (isDragging) snap() else tween(
            durationMillis = 400,
            easing = androidx.compose.animation.core.FastOutSlowInEasing,
        ),
        label = "highlightPos",
    )

    // highlightAlpha: 高亮刻度的透明度
    // 触摸时淡入（300ms），松手后延迟 300ms 再淡出（300ms）
    val highlightAlpha by animateFloatAsState(
        targetValue = if (highlightVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "highlightAlpha",
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    // playScale: 播放按钮缩放动画
    val playScale = remember { Animatable(1f) }
    LaunchedEffect(vm.isRunning) {
        playScale.animateTo(0.85f, tween(100))
        playScale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 400f))
    }

    // expandFraction: 任务面板的展开比例，0=未展开（时钟大小），1=完全展开（接近全屏）
    val expandFraction by animateFloatAsState(
        targetValue = if (showTaskPanel) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "expandFraction",
    )

    // panelAlpha: 任务面板内容的透明度
    val panelAlpha by animateFloatAsState(
        targetValue = if (showTaskPanel) 1f else 0f,
        animationSpec = tween(durationMillis = 300, delayMillis = if (showTaskPanel) 150 else 0),
        label = "panelAlpha",
    )

    val timerSize = 300.dp

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        // 主内容层
        Scaffold(
            contentWindowInsets = WindowInsets.statusBars,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "专注",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (vm.isRunning) "计时进行中..." else "点击/拖拽刻度设置时长",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenCompanion) {
                            Icon(
                                Icons.AutoMirrored.Filled.Chat,
                                contentDescription = "伙伴",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // ---- 计时器时钟 ----
                // 300dp 圆形，60 根圆角长条刻度 + 中间时间文字
                // 点击外圈刻度 → 吸附到最近的 5 分钟刻度；拖拽 → 实时调整时长
                // 点击中心区域 → 展开任务面板
                Box(
                    modifier = Modifier
                        .size(timerSize)
                        // 计时器运行时禁用刻度交互
                        .pointerInput(vm.isRunning) {
                            if (vm.isRunning) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val downPos = down.position
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val outerR = kotlin.math.min(size.width, size.height).toFloat() / 2f
                                // 刻度判断区域：外圈 50% 半径以上为刻度/拖拽区
                                val tickZoneStart = outerR * 0.5f

                                var dragged = false

                                /** 根据触点位置计算对应的分钟数（1~120，未吸附） */
                                fun minutesFromPosition(pos: Offset): Int {
                                    val dx = pos.x - center.x
                                    val dy = pos.y - center.y
                                    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                    var normalized = angle + 90f
                                    if (normalized < 0) normalized += 360f
                                    val fraction = (normalized / 360f).coerceIn(0f, 1f)
                                    return (1 + fraction * 119).roundToInt().coerceIn(1, 120)
                                }

                                // 等待后续事件，判断是点击还是拖拽
                                var lastPos = downPos
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (change.position != lastPos) {
                                        change.consume()
                                        dragged = true
                                        lastPos = change.position
                                        val newMinutes = minutesFromPosition(change.position)
                                        // 先更新时长再显示高亮，确保高亮出现在正确位置
                                        vm.setDuration(newMinutes)
                                        highlightVisible = true
                                    }
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                }

                                if (dragged) {
                                    // 拖拽结束：吸附到最近的 5 分钟整数倍
                                    val snapped = ((vm.focusMinutes + 2) / 5) * 5
                                    val final = snapped.coerceIn(5, 120)
                                    vm.setDuration(final)
                                } else {
                                    // 点击：根据触点位置判断操作
                                    val dx = downPos.x - center.x
                                    val dy = downPos.y - center.y
                                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                    if (dist >= tickZoneStart) {
                                        val rawMinutes = minutesFromPosition(downPos)
                                        val snapped = ((rawMinutes + 2) / 5) * 5
                                        val newMinutes = snapped.coerceIn(5, 120)
                                        vm.setDuration(newMinutes)
                                        // 点击刻度：短暂显示高亮反馈
                                        highlightVisible = true
                                    } else {
                                        // 点击中心区域 → 展开任务面板
                                        showTaskPanel = true
                                    }
                                }

                                // 松手后延迟关闭高亮：
                                // 使用 touchGeneration 防止旧的延迟协程被新的触摸误关
                                val myGen = ++touchGeneration
                                scope.launch {
                                    delay(300)
                                    if (touchGeneration == myGen) {
                                        highlightVisible = false
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // 圆角长条刻度 Canvas
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val outerRadius = size.minDimension / 2f - 8.dp.toPx()
                        // 根据进度计算已点亮的刻度数
                        val litCount = (animatedProgress.value * 60).roundToInt()

                        // 第一层：绘制 60 根基础刻度
                        for (i in 0 until 60) {
                            // 从12点位置开始顺时针
                            // 基础刻度在 Canvas 中初始位置就是 12 点方向，无需 -90° 偏移
                            // rotate(0°) = 留在 12 点，rotate(90°) = 顺时针到 3 点
                            val angleDeg = 360.0 * i / 60
                            val isLit = i < litCount
                            // 5 的倍数 = 时刻刻度，更长更宽
                            val isMajor = i % 5 == 0

                            val tickLength = if (isMajor) 22.dp.toPx() else 12.dp.toPx()
                            val tickWidth = if (isMajor) 3.5f.dp.toPx() else 2f.dp.toPx()

                            val tickColor = if (isLit) primaryColor
                                else primaryColor.copy(alpha = if (isMajor) 0.3f else 0.12f)
                            rotate(angleDeg.toFloat(), pivot = Offset(cx, cy)) {
                                drawRoundRect(
                                    color = tickColor,
                                    topLeft = Offset(
                                        cx - tickWidth / 2f,
                                        cy - outerRadius,
                                    ),
                                    size = Size(tickWidth, tickLength),
                                    cornerRadius = CornerRadius(tickWidth / 2f),
                                )
                            }
                        }

                        // 第二层：高亮刻度（触摸时淡入，松手后延迟 300ms 淡出）
                        // 使用 highlightPos 的连续角度值，精确跟随手指位置
                        if (highlightAlpha > 0.01f) {
                            // 高亮刻度角度与触摸计算的角度体系一致：0°=12点，顺时针递增
                            val hlAngleDeg = 360.0 * highlightPos
                            // 高亮刻度比普通刻度更长更粗，醒目的亮橙色
                            val hlLength = 26.dp.toPx()
                            val hlWidth = 4.5f.dp.toPx()
                            val hlColor = primaryColor.copy(alpha = highlightAlpha)
                            rotate(hlAngleDeg.toFloat(), pivot = Offset(cx, cy)) {
                                drawRoundRect(
                                    color = hlColor,
                                    topLeft = Offset(
                                        cx - hlWidth / 2f,
                                        cy - outerRadius,
                                    ),
                                    size = Size(hlWidth, hlLength),
                                    cornerRadius = CornerRadius(hlWidth / 2f),
                                )
                            }
                        }
                    }

                    // 中间内容：时间文字 + 任务名
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 48.dp),
                    ) {
                        Text(
                            timeText,
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontWeight = FontWeight.Light,
                                letterSpacing = 2.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (vm.selectedTaskTitle != null) {
                            Text(
                                vm.selectedTaskTitle!!,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text(
                                "${vm.focusMinutes} 分钟",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 播放控制行
                Spacer(modifier = Modifier.height(40.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 重置按钮：恢复到初始时长，清零已用秒数
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(52.dp),
                    ) {
                        IconButton(
                            onClick = { vm.reset() },
                            modifier = Modifier.size(52.dp),
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "重置",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(32.dp))
                    // 播放/暂停按钮：切换计时器运行状态
                    Surface(
                        shape = CircleShape,
                        color = primaryColor,
                        modifier = Modifier
                            .size(72.dp)
                            .scale(playScale.value)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { vm.toggleRunning() },
                            ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (vm.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (vm.isRunning) "暂停" else "开始",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(32.dp))
                    // 提前结束按钮：应用 5 分钟规则后重置
                    Surface(
                        shape = CircleShape,
                        color = if (vm.isRunning) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(52.dp),
                    ) {
                        IconButton(
                            onClick = { vm.stopEarly() },
                            modifier = Modifier.size(52.dp),
                            // 运行中或已消耗部分时间时才可点击
                            enabled = vm.isRunning || vm.remainingSeconds < vm.focusMinutes * 60,
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "提前结束",
                                tint = if (vm.isRunning) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ---- 任务选择面板（深橘色背景从时钟中心扩展/收缩） ----
        if (expandFraction > 0.01f) {
            TaskPanelOverlay(
                expandFraction = expandFraction,
                panelAlpha = panelAlpha,
                selectedTaskId = vm.selectedTaskId,
                onSelectTask = { taskId, taskTitle ->
                    vm.selectTask(taskId, taskTitle)
                    showTaskPanel = false
                },
                onDismiss = {
                    showTaskPanel = false
                },
            )
        }
    }
}

/**
 * 任务选择面板覆盖层 —— 背景从时钟中心向外扩展，内部显示带层级的任务列表。
 *
 * 动画原理：
 * - expandFraction 从 0 → 1：一个圆形遮罩从时钟大小扩展到覆盖全屏，
 *   视觉上像是从时钟中心「长出来」
 * - expandFraction 从 1 → 0：反向收缩回时钟中心
 * - 背景随扩展比例渐变出现
 *
 * 任务列表按父子层级展示，主任务（depth=0）显示标题+累计专注时长，
 * 子任务（depth>0）缩进显示仅标题，方便用户定位和选择。
 *
 * @param expandFraction 展开比例，0=时钟大小，1=全屏
 * @param panelAlpha 任务列表内容的透明度
 * @param selectedTaskId 当前已关联的任务 ID
 * @param onSelectTask 选择任务后的回调，传入 (任务ID, 任务标题)，null=不关联
 * @param onDismiss 点击空白区域关闭面板的回调
 */
@Composable
private fun TaskPanelOverlay(
    expandFraction: Float,
    panelAlpha: Float,
    selectedTaskId: String?,
    onSelectTask: (taskId: String?, taskTitle: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val vm = focusSetupViewModel()
    LaunchedEffect(Unit) { vm.loadTasks() }

    val primaryColor = MaterialTheme.colorScheme.primary
    val panelBg = MaterialTheme.colorScheme.surface

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // 扩展的圆形背景
        // 初始大小 = 时钟 300dp，最终覆盖全屏
        // 使用 graphicsLayer 的 scaleX/scaleY 配合 clip 实现圆形扩展效果
        val initialSize = 300.dp
        Box(
            modifier = Modifier
                .size(initialSize)
                .graphicsLayer {
                    // 从 1x 扩展到 5x（覆盖全屏）
                    val s = 1f + expandFraction * 4f
                    scaleX = s
                    scaleY = s
                    // 透明度随扩展渐变
                    alpha = expandFraction
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .clip(CircleShape)
                .background(panelBg)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        // 任务列表内容（在扩展背景之上）
        if (panelAlpha > 0.01f) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = panelAlpha }
                    .padding(horizontal = 24.dp)
                    .padding(top = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 面板标题
                Text(
                    "选择专注任务",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                )

                // 清单筛选 Chip 行：紧跟标题下方，横向滚动
                // "全部" chip + 每个清单一个 chip，点击切换筛选并重新加载任务
                if (vm.checklists.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // "全部" chip：selectedChecklistId == null 时选中
                        item {
                            val isAll = vm.selectedChecklistId == null
                            FilterChip(
                                selected = isAll,
                                onClick = { vm.setChecklistFilter(null) },
                                label = { Text("全部") },
                                colors = if (isAll) FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = primaryColor,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ) else FilterChipDefaults.filterChipColors(),
                            )
                        }
                        // 各清单 chip
                        items(vm.checklists, key = { it.id }) { checklist ->
                            val isSelected = vm.selectedChecklistId == checklist.id
                            FilterChip(
                                selected = isSelected,
                                onClick = { vm.setChecklistFilter(checklist.id) },
                                label = { Text(checklist.name) },
                                colors = if (isSelected) FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = primaryColor,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ) else FilterChipDefaults.filterChipColors(),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "不选择则以空任务专注",
                    style = MaterialTheme.typography.bodySmall,
                    color = primaryColor.copy(alpha = 0.6f),
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (vm.flatTasks.isEmpty()) {
                    Text(
                        "暂无待办任务",
                        style = MaterialTheme.typography.bodyLarge,
                        color = primaryColor.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // "不关联任务"选项卡
                        item {
                            val noneSelected = selectedTaskId == null
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                border = if (noneSelected) BorderStroke(2.dp, primaryColor) else null,
                                onClick = { onSelectTask(null, null) },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "不关联任务",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (noneSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (noneSelected) primaryColor else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            "空任务专注",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (noneSelected) primaryColor.copy(alpha = 0.7f)
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (noneSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = primaryColor,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                            }
                        }
                        // 层级任务列表
                        items(vm.flatTasks, key = { it.task.id }) { item ->
                            HierarchicalTaskCard(
                                item = item,
                                selectedTaskId = selectedTaskId,
                                onSelectTask = onSelectTask,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 格式化累计专注时间（秒 → 可读字符串）。
 *
 * @param seconds 累计秒数
 * @return 格式化后的字符串，如 "2小时30分钟"、"45分钟"、"从未专注"
 */
private fun formatFocusTime(seconds: Long): String {
    if (seconds <= 0) return "从未专注"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}小时${minutes}分钟"
        hours > 0 -> "${hours}小时"
        minutes > 0 -> "${minutes}分钟"
        else -> "不到1分钟"
    }
}

/**
 * 层级任务卡片 —— 根据深度缩进，展示父子任务关系。
 *
 * depth=0 为主任务，使用较大卡片和完整信息（标题 + 累计专注时长）；
 * depth>0 为子任务，缩进显示，仅展示标题，卡片更紧凑。
 * 选中态使用主题色边框 + 加粗文字 + 勾选图标。
 *
 * @param item 展平后的任务项（含深度和父子关系信息）
 * @param selectedTaskId 当前选中的任务 ID
 * @param onSelectTask 点击选择任务的回调，传入 (任务ID, 任务标题)
 */
@Composable
private fun HierarchicalTaskCard(
    item: FlatTaskItem,
    selectedTaskId: String?,
    onSelectTask: (taskId: String?, taskTitle: String?) -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val isSelected = selectedTaskId == item.task.id
    val depth = item.depth

    Row(modifier = Modifier.fillMaxWidth()) {
        // 根据 depth 缩进：每层 20.dp，子任务视觉上嵌套在父任务下方
        if (depth > 0) {
            Spacer(modifier = Modifier.width((depth * 20).dp))
        }
        Surface(
            modifier = Modifier.weight(1f),
            // 主任务使用大圆角，子任务使用小圆角以视觉区分层级
            shape = RoundedCornerShape(if (depth == 0) 16.dp else 12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = if (isSelected) BorderStroke(2.dp, primaryColor) else null,
            onClick = { onSelectTask(item.task.id, item.task.title) },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        // 子任务内边距更紧凑
                        horizontal = if (depth == 0) 20.dp else 14.dp,
                        vertical = if (depth == 0) 16.dp else 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.task.title,
                        // 子任务使用较小字号
                        style = if (depth == 0) MaterialTheme.typography.bodyLarge
                            else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isSelected) primaryColor
                            else MaterialTheme.colorScheme.onSurface,
                    )
                    // 主任务显示累计专注时长副标题，子任务不显示
                    if (depth == 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            formatFocusTime(item.task.focusSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) primaryColor.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 选中时显示勾选图标
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = primaryColor,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
