package com.desknote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.desknote.AppController
import com.desknote.NoteState
import com.desknote.taskProgress
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private val MenuWidth = 250.dp
private val MenuInk = Color(0xFF2E2A24)
private const val VISIBLE_ROWS = 8
private const val ROW_HEIGHT = 30

@Composable
fun TrayMenuWindow(controller: AppController, onExit: () -> Unit) {
    val menu = controller.trayMenu ?: return
    val scale = remember {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
    }

    val notes = controller.notes
    val visibleRows = minOf(notes.size, VISIBLE_ROWS)
    // 基础项：新建/搜索 + 排列 + 显隐 + 自启 + 局域网接口 + 退出 + 分隔线 + 上下留白
    val baseHeight = 284 + 5 + 32 +
        (if (controller.apiEnabled) 32 else 0) +
        (if (controller.apiError != null) 32 else 0)
    val menuHeight = (baseHeight + visibleRows * ROW_HEIGHT).dp

    val positionX = maxOf(0.dp, (menu.x / scale).dp - MenuWidth)
    val positionY = maxOf(0.dp, (menu.y / scale).dp - menuHeight)

    val windowState = rememberWindowState(
        position = WindowPosition.Absolute(positionX, positionY),
        size = DpSize(MenuWidth, menuHeight)
    )

    Window(
        onCloseRequest = { controller.closeTrayMenu() },
        state = windowState,
        title = "一叶便签 菜单",
        icon = AppIcon.painter(),
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true
    ) {
        DisposableEffect(window) {
            val listener = object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) {
                    controller.closeTrayMenu()
                }
            }
            window.addWindowListener(listener)
            onDispose { window.removeWindowListener(listener) }
        }
        LaunchedEffect(Unit) {
            window.toFront()
            window.requestFocus()
        }

        Column(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFF7F7F8))
                .padding(vertical = 4.dp)
        ) {
            MenuRow("新建便签  Ctrl+Alt+N") {
                controller.closeTrayMenu()
                controller.newNote()
            }
            MenuRow("搜索便签  Ctrl+Alt+F") {
                controller.closeTrayMenu()
                controller.openSearch()
            }
            Separator()
            if (notes.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(ROW_HEIGHT.dp).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text("还没有便签", color = Color(0x662E2A24), fontSize = 12.sp)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height((visibleRows * ROW_HEIGHT).dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    notes.forEach { note ->
                        NoteRow(
                            note = note,
                            onFocus = {
                                controller.closeTrayMenu()
                                controller.focusNote(note.id)
                            },
                            onToggle = { controller.toggleVisible(note.id) }
                        )
                    }
                }
            }
            Separator()
            MenuRow("网格排列") {
                controller.closeTrayMenu()
                controller.arrangeGrid()
            }
            MenuRow("右上角堆叠") {
                controller.closeTrayMenu()
                controller.arrangeStack()
            }
            Separator()
            MenuRow("显示全部  Ctrl+Alt+H") {
                controller.closeTrayMenu()
                controller.showAll()
            }
            MenuRow("隐藏全部  Ctrl+Alt+H") {
                controller.closeTrayMenu()
                controller.hideAll()
            }
            MenuRow(if (controller.autostartEnabled) "开机自启  ✓" else "开机自启") {
                controller.toggleAutostart()
                controller.closeTrayMenu()
            }
            Separator()
            MenuRow(if (controller.apiEnabled) "局域网接口  ✓" else "局域网接口") {
                controller.toggleApi()
                controller.closeTrayMenu()
            }
            if (controller.apiEnabled) {
                MenuRow("复制接口信息") {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(StringSelection(controller.apiInfo()), null)
                }
            }
            if (controller.apiError != null) {
                Box(
                    Modifier.fillMaxWidth().height(ROW_HEIGHT.dp).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        "接口启动失败：${controller.apiError}",
                        color = Color(0xFFB04A3A),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Separator()
            MenuRow("退出") {
                controller.closeTrayMenu()
                controller.saveNow()
                onExit()
            }
        }
    }
}

@Composable
private fun NoteRow(note: NoteState, onFocus: () -> Unit, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT.dp)
            .clickable(onClick = onFocus)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(Color(note.color.argb))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = note.displayTitle(),
            modifier = Modifier.weight(1f),
            color = if (note.visible) MenuInk else Color(0x662E2A24),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier.padding(horizontal = 3.dp)
        ) {
            val progress = taskProgress(note.text)
            if (progress.second > 0) {
                Text(
                    text = "${progress.first}/${progress.second}",
                    color = if (progress.first >= progress.second) Color(0xFF4F7A58) else Color(0x772E2A24),
                    fontSize = 9.sp
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (note.visible) "✓" else "○",
                color = if (note.visible) Color(0xFF2E7D54) else Color(0x552E2A24),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, color = MenuInk, fontSize = 13.sp)
    }
}

@Composable
private fun Separator() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(1.dp)
            .background(Color(0x14000000))
    )
}
