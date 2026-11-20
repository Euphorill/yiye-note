package com.desknote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.desknote.AppController
import com.desknote.NoteState
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private val PanelWidth = 340.dp
private val PanelHeight = 500.dp
private val PanelInk = Color(0xFF2E2A24)
private val PanelMuted = Color(0x992E2A24)

/** 便签抽屉：Ctrl+Alt+F 打开，列所有便签，点击定位并闪烁 */
@Composable
fun SearchPanelWindow(controller: AppController) {
    var query by remember { mutableStateOf("") }
    val results = controller.search(query)

    val scale = remember {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
    }
    val screenWidth = Toolkit.getDefaultToolkit().screenSize.width
    val positionX = maxOf(24.dp, (screenWidth / scale).dp - PanelWidth - 24.dp)

    val windowState = rememberWindowState(
        position = WindowPosition.Absolute(positionX, 40.dp),
        size = DpSize(PanelWidth, PanelHeight)
    )

    val focusRequester = remember { FocusRequester() }

    Window(
        onCloseRequest = { controller.closeSearch() },
        state = windowState,
        title = "一叶便签 搜索",
        icon = AppIcon.painter(),
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true
    ) {
        DisposableEffect(window) {
            val listener = object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) {
                    controller.closeSearch()
                }
            }
            window.addWindowListener(listener)
            onDispose { window.removeWindowListener(listener) }
        }
        LaunchedEffect(Unit) {
            window.toFront()
            window.requestFocus()
            focusRequester.requestFocus()
        }

        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF7FAF7F2))
                .border(1.dp, Color(0x22000000), RoundedCornerShape(12.dp))
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        controller.closeSearch()
                        true
                    } else {
                        false
                    }
                }
                .padding(14.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Enter -> {
                                    results.firstOrNull()?.let { note ->
                                        controller.focusNote(note.id)
                                        controller.closeSearch()
                                    }
                                    true
                                }

                                Key.Escape -> {
                                    controller.closeSearch()
                                    true
                                }

                                else -> false
                            }
                        },
                    textStyle = TextStyle(color = PanelInk, fontSize = 14.sp),
                    cursorBrush = SolidColor(PanelInk),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text("搜索所有便签…", color = PanelMuted, fontSize = 14.sp)
                            }
                            inner()
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14000000)))
                Spacer(Modifier.height(6.dp))
                Text("${results.size} 张便签", color = PanelMuted, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (results.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("没有匹配的便签", color = PanelMuted, fontSize = 12.sp)
                        }
                    }
                    results.forEach { note ->
                        ResultRow(note) {
                            controller.focusNote(note.id)
                            controller.closeSearch()
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SmallButton("网格排列") { controller.arrangeGrid() }
                    SmallButton("右上角堆叠") { controller.arrangeStack() }
                    Spacer(Modifier.weight(1f))
                    Text("Esc 关闭", color = PanelMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun ResultRow(note: NoteState, onClick: () -> Unit) {
    val summary = note.text.replace('\n', ' ').trim()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(Color(note.color.argb))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = note.displayTitle(),
                modifier = Modifier.weight(1f),
                color = if (note.visible) PanelInk else PanelMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(note.color.label, color = PanelMuted, fontSize = 9.sp)
        }
        if (summary.isNotEmpty()) {
            Text(
                text = summary.take(70),
                modifier = Modifier.padding(start = 15.dp, top = 2.dp),
                color = PanelMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x1A000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = PanelInk,
        fontSize = 11.sp
    )
}
