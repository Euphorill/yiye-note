package com.desknote.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.desknote.AppController
import com.desknote.NoteColor
import com.desknote.NoteState
import com.desknote.WindowLevel
import com.desknote.parseTaskLine
import com.desknote.taskProgress
import com.desknote.platform.FullscreenWatcher
import com.desknote.platform.WindowLayer
import java.awt.EventQueue
import java.awt.MouseInfo
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

private val Ink = Color(0xFF2E2A24)
private val InkMuted = Color(0x992E2A24)
private val CardShape = RoundedCornerShape(10.dp)
private val ShadowPad = 10.dp
private val HeaderHeight = 36.dp

private fun collapsedWindowHeight() = ShadowPad * 2 + HeaderHeight

@Composable
fun NoteWindow(note: NoteState, controller: AppController) {
    // 位置完全由我们自己接管（首次 setLocation + 拖拽），不让 WindowState 参与，避免拖动时被拉回
    val initial = remember(note.id) { note }
    val windowState = rememberWindowState(
        size = DpSize(
            initial.width.dp,
            if (initial.collapsed) collapsedWindowHeight() else initial.height.dp
        )
    )

    // 阅读态 / 编辑态：空便签直接进入编辑，锁定的便签永远只读
    var editing by remember(note.id) { mutableStateOf(initial.text.isEmpty() && initial.visible) }
    var hiddenByFullscreen by remember { mutableStateOf(false) }
    var flash by remember(note.id) { mutableStateOf(false) }

    val currentCollapsed by rememberUpdatedState(note.collapsed)
    val currentLevel by rememberUpdatedState(note.level)

    Window(
        onCloseRequest = { controller.hide(note.id) },
        state = windowState,
        visible = note.visible && !hiddenByFullscreen,
        title = "一叶便签",
        icon = AppIcon.painter(),
        undecorated = true,
        transparent = true,
        resizable = true,
        alwaysOnTop = false
    ) {
        val level by rememberUpdatedState(note.level)
        val density = LocalDensity.current
        val noteId = note.id

        LaunchedEffect(Unit) {
            // 工具窗口：任务栏的「显示桌面」不会把便签最小化
            WindowLayer.makeToolWindow(window)
        }

        // 坐标从状态同步到窗口：拖拽结束保存的坐标与窗口一致，不会回跳；排列命令则靠这里生效
        LaunchedEffect(note.x, note.y) {
            val scale = window.graphicsConfiguration.defaultTransform.scaleX
            val targetX = (note.x * scale).roundToInt()
            val targetY = (note.y * scale).roundToInt()
            if (abs(window.x - targetX) > 2 || abs(window.y - targetY) > 2) {
                window.setLocation(targetX, targetY)
                window.repaint()
            }
        }

        // 折叠：窗口缩到只留标题条；展开时恢复记住的高度
        LaunchedEffect(note.collapsed, note.height, note.width) {
            val targetHeight = with(density) {
                if (note.collapsed) collapsedWindowHeight().toPx() else note.height.dp.toPx()
            }.roundToInt()
            val targetWidth = with(density) { note.width.dp.toPx() }.roundToInt()
            if (window.height != targetHeight || window.width != targetWidth) {
                window.setSize(targetWidth, targetHeight)
                window.repaint()
            }
            window.isResizable = !note.collapsed
        }

        LaunchedEffect(level) {
            WindowLayer.apply(window, level)
        }

        // 智能置顶：有全屏窗口时收起便签，全屏结束后恢复；由 WinEvent 事件驱动，不做轮询
        DisposableEffect(level, window) {
            if (level != WindowLevel.SMART) {
                hiddenByFullscreen = false
                return@DisposableEffect onDispose { }
            }
            val listener: (Boolean) -> Unit = { fullscreen ->
                EventQueue.invokeLater {
                    hiddenByFullscreen = fullscreen
                    WindowLayer.applySmart(window, fullscreen)
                }
            }
            FullscreenWatcher.addListener(listener)
            onDispose {
                FullscreenWatcher.removeListener(listener)
                hiddenByFullscreen = false
            }
        }

        // 从托盘 / 搜索面板定位到这张便签：置前、拿焦点、闪一下
        LaunchedEffect(controller.focusRequest) {
            val request = controller.focusRequest ?: return@LaunchedEffect
            if (request.id != noteId) return@LaunchedEffect
            window.toFront()
            window.requestFocus()
            flash = true
            kotlinx.coroutines.delay(1100)
            flash = false
        }

        LaunchedEffect(note.locked) {
            if (note.locked) editing = false
        }

        DisposableEffect(window, noteId) {
            val windowListener = object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) {
                    // 失焦即退出编辑态，避免透明窗口里误改内容
                    editing = false
                    if (currentLevel == WindowLevel.BOTTOM) WindowLayer.pushToBottom(window)
                    persistBounds(noteId, controller, window, currentCollapsed)
                }
            }
            // 便签窗口有焦点时 Ctrl+F 直接开搜索面板（全局热键是 Ctrl+Alt+F，不抢占别的程序）
            val keyListener = object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.isControlDown && e.keyCode == KeyEvent.VK_F) {
                        controller.openSearch()
                    }
                }
            }
            window.addWindowListener(windowListener)
            window.addKeyListener(keyListener)
            onDispose {
                window.removeWindowListener(windowListener)
                window.removeKeyListener(keyListener)
            }
        }

        NoteSurface(
            note = note,
            controller = controller,
            window = window,
            editing = editing,
            onEditingChange = { editing = it },
            flash = flash
        )
    }
}

private fun persistBounds(
    id: String,
    controller: AppController,
    window: java.awt.Window,
    collapsed: Boolean
) {
    runCatching {
        // AWT 坐标是物理像素，Compose 的 WindowPosition 用 dp，按屏幕缩放换算后保存
        val scale = window.graphicsConfiguration.defaultTransform.scaleX
        controller.updateBounds(
            id,
            (window.x / scale).roundToInt(),
            (window.y / scale).roundToInt(),
            (window.width / scale).roundToInt(),
            if (collapsed) null else (window.height / scale).roundToInt()
        )
    }
}

@Composable
private fun NoteSurface(
    note: NoteState,
    controller: AppController,
    window: java.awt.Window,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    flash: Boolean
) {
    // 编辑时摆正，阅读时微倾——有位置感又不影响看内容
    val tilt by animateFloatAsState(
        targetValue = if (editing || note.collapsed) 0f else tiltOf(note.id),
        label = "tilt"
    )

    Box(Modifier.fillMaxSize().padding(ShadowPad)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = tilt }
                .shadow(
                    elevation = if (editing || note.collapsed) 4.dp else 10.dp,
                    shape = CardShape,
                    clip = false
                )
                .clip(CardShape)
                .background(Color(note.color.argb))
                .paperTexture(note.id.hashCode())
                .then(
                    if (flash) Modifier.border(2.dp, Color(0xFFFFA43B), CardShape) else Modifier
                )
        ) {
            Column(Modifier.fillMaxSize()) {
                Header(note, controller, window, editing, onEditingChange)
                if (!note.collapsed) {
                    Body(note, controller, editing, onEditingChange)
                }
            }
            if (!note.collapsed) {
                Tape(Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

/** 纸纹 + 顶部压痕，让便签不像一块纯色矩形 */
private fun Modifier.paperTexture(seed: Int): Modifier = this.drawWithContent {
    drawContent()
    val random = Random(seed)
    val speckle = Color(0x142E2A24)
    repeat(150) {
        drawCircle(
            color = speckle,
            radius = 0.5f + random.nextFloat() * 1.1f,
            center = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
        )
    }
    drawLine(
        color = Color(0x0F2E2A24),
        start = Offset(0f, 0f),
        end = Offset(size.width, 0f),
        strokeWidth = 3f
    )
}

/** 顶部一小条半透明“胶带” */
@Composable
private fun Tape(modifier: Modifier) {
    Box(
        modifier
            .offset(y = 2.dp)
            .size(width = 64.dp, height = 14.dp)
            .graphicsLayer { rotationZ = -2.5f }
            .background(Color(0x3CFFFFFF), RoundedCornerShape(2.dp))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(2.dp))
    )
}

private fun tiltOf(id: String): Float {
    // 由 id 决定固定倾角：-1.8° ~ +1.8°，不落在 0，重开也保持同一个“贴上去的角度”
    val bucket = (abs(id.hashCode()) % 4)
    return (bucket - 1.5f) * 1.2f
}

@Composable
private fun Header(
    note: NoteState,
    controller: AppController,
    window: java.awt.Window,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HeaderHeight)
            .pointerInput(window) {
                // 关键：不用 dragAmount 累加。窗口移动会让指针相对窗口的坐标变化，累加会产生
                // 反馈回环（拖起来来回抖）。改为锚定“鼠标屏幕坐标 + 初始窗口坐标”的差值。
                var startWinX = 0
                var startWinY = 0
                var startMouseX = 0
                var startMouseY = 0
                detectDragGestures(
                    onDragStart = {
                        val p = MouseInfo.getPointerInfo()?.location
                        startWinX = window.x
                        startWinY = window.y
                        if (p != null) {
                            startMouseX = p.x
                            startMouseY = p.y
                        }
                    },
                    onDragEnd = {
                        window.repaint()
                        persistBounds(note.id, controller, window, note.collapsed)
                    },
                    onDragCancel = {
                        persistBounds(note.id, controller, window, note.collapsed)
                    }
                ) { change, _ ->
                    change.consume()
                    val p = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                    window.setLocation(
                        startWinX + (p.x - startMouseX),
                        startWinY + (p.y - startMouseY)
                    )
                    // 移动透明窗口后系统只补画新暴露的区域，圆角外会残留旧的矩形像素，强制整窗重绘
                    window.repaint()
                }
            }
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PinIcon(note.level)
        Spacer(Modifier.width(6.dp))
        Text(
            text = note.displayTitle(),
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = !note.locked) {
                    if (note.collapsed) controller.setCollapsed(note.id, false) else onEditingChange(true)
                },
            color = Ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (note.locked) {
            LockChip { controller.setLocked(note.id, false) }
        }
        val progress = taskProgress(note.text)
        if (progress.second > 0) {
            Spacer(Modifier.width(4.dp))
            ProgressChip(progress.first, progress.second)
        }
        Spacer(Modifier.width(4.dp))
        LevelChip(note.level) { controller.cycleLevel(note.id) }
        CollapseButton(note.collapsed) { collapsed ->
            if (collapsed) persistBounds(note.id, controller, window, false)
            controller.setCollapsed(note.id, collapsed)
        }
        MenuButton(note, controller, editing, onEditingChange)
    }
}

@Composable
private fun PinIcon(level: WindowLevel) {
    if (level != WindowLevel.TOP && level != WindowLevel.SMART) return
    val tint = if (level == WindowLevel.TOP) Color(0xFFB5543F) else Color(0xFF7A6A55)
    Canvas(Modifier.size(11.dp)) {
        val w = size.width
        val h = size.height
        drawCircle(tint, radius = w * 0.24f, center = Offset(w * 0.5f, h * 0.3f))
        val path = Path().apply {
            moveTo(w * 0.32f, h * 0.34f)
            lineTo(w * 0.68f, h * 0.34f)
            lineTo(w * 0.5f, h * 0.82f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
private fun LockChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x22000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text("已锁", color = InkMuted, fontSize = 10.sp)
    }
}

@Composable
private fun ProgressChip(done: Int, total: Int) {
    val allDone = done >= total
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (allDone) Color(0x2E7E9C82) else Color(0x1A000000))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "$done/$total",
            color = if (allDone) Color(0xFF4F7A58) else InkMuted,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun LevelChip(level: WindowLevel, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x1A000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(level.label, color = InkMuted, fontSize = 10.sp)
    }
}

@Composable
private fun CollapseButton(collapsed: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onChange(!collapsed) }
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(if (collapsed) "▸" else "▾", color = InkMuted, fontSize = 11.sp)
    }
}

@Composable
private fun MenuButton(
    note: NoteState,
    controller: AppController,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    DismissOnOutsideClick(expanded) { expanded = false }
    val index = controller.notes.indexOfFirst { it.id == note.id } + 1
    Box {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text("···", color = InkMuted, fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                text = "#$index · 最后修改 ${formatTime(note.updatedAt)}",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                color = InkMuted,
                fontSize = 10.sp
            )
            DropdownMenuItem(
                text = { Text(if (editing) "完成编辑" else "编辑内容") },
                onClick = {
                    expanded = false
                    onEditingChange(!editing)
                }
            )
            DropdownMenuItem(
                text = { Text(if (note.locked) "解锁" else "锁定（只读）") },
                onClick = {
                    expanded = false
                    controller.setLocked(note.id, !note.locked)
                }
            )
            DropdownMenuItem(
                text = { Text(if (note.collapsed) "展开" else "折叠") },
                onClick = {
                    expanded = false
                    controller.setCollapsed(note.id, !note.collapsed)
                }
            )
            DropdownMenuItem(
                text = { Text("新建便签") },
                onClick = {
                    expanded = false
                    controller.newNote()
                }
            )
            DropdownMenuItem(
                text = { Text("层级：${note.level.label}") },
                onClick = { controller.cycleLevel(note.id) }
            )
            DropdownMenuItem(
                text = { Text("网格排列") },
                onClick = {
                    expanded = false
                    controller.arrangeGrid()
                }
            )
            DropdownMenuItem(
                text = { Text("右上角堆叠") },
                onClick = {
                    expanded = false
                    controller.arrangeStack()
                }
            )
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NoteColor.entries.forEach { color ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(color.argb))
                            .border(
                                width = if (color == note.color) 1.5.dp else 1.dp,
                                color = if (color == note.color) Ink else Color(0x33000000),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable { controller.setColor(note.id, color) }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(color.label, color = Ink, fontSize = 10.sp)
                    }
                }
            }
            DropdownMenuItem(
                text = { Text("隐藏") },
                onClick = {
                    expanded = false
                    controller.hide(note.id)
                }
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    expanded = false
                    controller.delete(note.id)
                }
            )
        }
    }
}

@Composable
private fun Body(
    note: NoteState,
    controller: AppController,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit
) {
    if (editing && !note.locked) {
        EditBody(note, controller, onEditingChange)
    } else {
        ReadBody(note, controller, onEditingChange)
    }
}

@Composable
private fun ReadBody(
    note: NoteState,
    controller: AppController,
    onEditingChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .clickable(enabled = !note.locked) { onEditingChange(true) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (note.text.isBlank()) {
            Text("点击写点什么…", color = InkMuted, fontSize = 13.sp)
        } else {
            SelectionContainer {
                Column {
                    note.text.split('\n').forEachIndexed { index, line ->
                        val task = parseTaskLine(line)
                        if (task == null) {
                            Text(line, color = Ink, fontSize = 14.sp, lineHeight = 21.sp)
                        } else {
                            // 点这一行只切换勾选，不进入编辑态
                            TaskRow(
                                done = task.first,
                                content = task.second,
                                enabled = !note.locked,
                                onToggle = { controller.toggleTask(note.id, index) }
                            )
                        }
                    }
                }
            }
        }
        if (note.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                note.tags.forEach { tag ->
                    Text(
                        text = tag,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x1F000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        color = InkMuted,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRow(done: Boolean, content: String, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle
            )
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(13.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (done) Color(0xFF7E9C82) else Color(0x00000000))
                .border(
                    width = 1.5.dp,
                    color = if (done) Color(0xFF7E9C82) else Color(0x66000000),
                    shape = RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Text("✓", color = Color.White, fontSize = 9.sp, lineHeight = 9.sp)
            }
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = content.ifEmpty { "（空待办）" },
            color = if (done) Color(0x772E2A24) else Ink,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textDecoration = if (done) TextDecoration.LineThrough else null
        )
    }
}

@Composable
private fun EditBody(
    note: NoteState,
    controller: AppController,
    onEditingChange: (Boolean) -> Unit
) {
    val titleFocus = remember(note.id) { FocusRequester() }
    // 用 TextFieldValue 自己持有文本、光标与输入法组词状态，避免把输入法拼音当成正文回写后破坏组词
    var titleValue by remember(note.id) { mutableStateOf(TextFieldValue(note.title)) }
    var textValue by remember(note.id) { mutableStateOf(TextFieldValue(note.text)) }
    var tagValue by remember(note.id) { mutableStateOf(TextFieldValue(note.tags.joinToString(" "))) }

    LaunchedEffect(note.id) { titleFocus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onEditingChange(false)
                    true
                } else {
                    false
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        BasicTextField(
            value = titleValue,
            onValueChange = { value ->
                titleValue = value
                controller.updateTitle(note.id, value.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(titleFocus),
            textStyle = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(Ink),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (titleValue.text.isEmpty()) {
                        Text("标题（留空取首行）", color = Color(0x552E2A24), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    inner()
                }
            }
        )
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = textValue,
            onValueChange = { value ->
                textValue = value
                controller.updateText(note.id, value.text)
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = Ink, fontSize = 13.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(Ink),
            decorationBox = { inner ->
                Box {
                    if (textValue.text.isEmpty()) {
                        Text("写点什么…（- [ ] 或 -（）开头即待办）", color = InkMuted, fontSize = 11.sp)
                    }
                    inner()
                }
            }
        )
        Spacer(Modifier.height(10.dp))
        BasicTextField(
            value = tagValue,
            onValueChange = { value ->
                tagValue = value
                controller.updateTags(note.id, parseTags(value.text))
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = InkMuted, fontSize = 11.sp),
            cursorBrush = SolidColor(InkMuted),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (tagValue.text.isEmpty()) {
                        Text("标签（空格或逗号分隔）", color = Color(0x552E2A24), fontSize = 11.sp)
                    }
                    inner()
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = "完成",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x22000000))
                    .clickable { onEditingChange(false) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = Ink,
                fontSize = 11.sp
            )
        }
    }
}

private fun parseTags(raw: String): List<String> =
    raw.split(',', '，', ' ', '、', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(3)

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
}
