package com.desknote.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.sun.jna.platform.win32.User32
import kotlinx.coroutines.delay
import java.awt.MouseInfo
import java.awt.Window

/**
 * 弹出菜单打开后，点击本应用之外的区域也关闭。
 *
 * Compose 的 DropdownMenu 只能感知自身窗口内的点击；点击桌面或其它程序时
 * 不会触发 onDismissRequest，所以这里轮询鼠标按键，在“按下后抬起”时判断
 * 落点是否还在本进程的任一窗口内。弹窗（DropdownMenu 的 Popup）本身也是
 * 独立窗口，点菜单项不会被误判为外部点击。
 */
@Composable
internal fun DismissOnOutsideClick(active: Boolean, onDismiss: () -> Unit) {
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        var wasDown = false
        while (true) {
            delay(30)
            val down = isLeftButtonDown()
            val settled = System.currentTimeMillis() - startedAt > 200
            if (settled && wasDown && !down) {
                val point = MouseInfo.getPointerInfo()?.location
                val inside = point != null && Window.getWindows().any { it.isVisible && it.bounds.contains(point) }
                if (!inside) onDismiss()
            }
            wasDown = down
        }
    }
}

private const val VK_LBUTTON = 0x01

private fun isLeftButtonDown(): Boolean =
    (User32.INSTANCE.GetAsyncKeyState(VK_LBUTTON).toInt() and 0x8000) != 0
