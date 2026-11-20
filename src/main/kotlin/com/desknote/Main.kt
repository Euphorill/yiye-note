package com.desknote

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.desknote.platform.Hotkeys
import com.desknote.platform.SingleInstance
import com.desknote.platform.ShowRequestWatcher
import com.desknote.ui.NoteWindow
import com.desknote.ui.SearchPanelWindow
import com.desknote.ui.TrayController
import com.desknote.ui.TrayMenuWindow
import java.awt.EventQueue

fun main() {
    // 单实例：已经有实例在跑就让它显示全部便签，然后自己退出
    if (!SingleInstance.acquire()) {
        SingleInstance.requestShow()
        return
    }

    application {
        val controller = remember { AppController() }
        val tray = remember { TrayController(controller) }

        DisposableEffect(Unit) {
            tray.install()
            Hotkeys.start { action ->
                EventQueue.invokeLater { controller.handleHotkey(action) }
            }
            ShowRequestWatcher.start {
                EventQueue.invokeLater { controller.showAll() }
            }
            onDispose {
                tray.remove()
                Hotkeys.stop()
                ShowRequestWatcher.stop()
            }
        }

        DisposableEffect(Unit) {
            val hook = Thread { controller.saveNow() }
            Runtime.getRuntime().addShutdownHook(hook)
            onDispose { runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
        }

        if (controller.trayMenu != null) {
            key("tray-menu") {
                TrayMenuWindow(controller, onExit = { exitApplication() })
            }
        }

        if (controller.searchOpen) {
            key("search-panel") {
                SearchPanelWindow(controller)
            }
        }

        // 便签窗口始终留在组合里，用 visible 控制显示：全部隐藏时如果没有窗口存在，
        // Compose 的 application 会直接结束进程，托盘也就跟着没了。
        controller.notes.forEach { note ->
            key(note.id) {
                NoteWindow(note, controller)
            }
        }
    }
}
