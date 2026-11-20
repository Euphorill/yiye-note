package com.desknote.platform

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser.MSG
import com.sun.jna.win32.StdCallLibrary

/**
 * 全局热键：Ctrl+Alt+N 新建 / Ctrl+Alt+H 显隐全部 / Ctrl+Alt+↑↓ 轮换聚焦 / Ctrl+Alt+F 搜索。
 * RegisterHotKey 注册在独立线程上，用 GetMessage 收 WM_HOTKEY，不额外开窗口。
 */
object Hotkeys {
    const val NEW = 1
    const val TOGGLE_ALL = 2
    const val NEXT = 3
    const val PREV = 4
    const val SEARCH = 5

    private const val MOD_ALT = 0x0001
    private const val MOD_CONTROL = 0x0002
    private const val MOD_NOREPEAT = 0x4000
    private const val WM_HOTKEY = 0x0312

    private const val VK_F = 0x46
    private const val VK_H = 0x48
    private const val VK_N = 0x4E
    private const val VK_UP = 0x26
    private const val VK_DOWN = 0x28

    private interface User32Ex : StdCallLibrary {
        fun RegisterHotKey(hWnd: HWND?, id: Int, fsModifiers: Int, vk: Int): Boolean
        fun UnregisterHotKey(hWnd: HWND?, id: Int): Boolean

        // user32 里只有 GetMessageA/W，按字面名解析，不走 Unicode 映射
        fun GetMessageA(msg: MSG, hWnd: HWND?, wMsgFilterMin: Int, wMsgFilterMax: Int): Int
    }

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
    private val bindings = listOf(
        NEW to VK_N,
        TOGGLE_ALL to VK_H,
        NEXT to VK_UP,
        PREV to VK_DOWN,
        SEARCH to VK_F
    )

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start(handler: (Int) -> Unit) {
        if (!isWindows || running) return
        running = true
        thread = Thread({
            runCatching {
                // 直接按字面名加载，RegisterHotKey / GetMessageA 都原样查找
                val user32 = Native.load("user32", User32Ex::class.java)
                val mods = MOD_CONTROL or MOD_ALT or MOD_NOREPEAT
                bindings.forEach { (id, vk) -> user32.RegisterHotKey(null, id, mods, vk) }
                val msg = MSG()
                while (running) {
                    val result = user32.GetMessageA(msg, null, 0, 0)
                    if (result <= 0) break
                    if (msg.message == WM_HOTKEY) {
                        val id = msg.wParam.toString().toIntOrNull()
                        if (id != null) handler(id)
                    }
                }
                bindings.forEach { (id, _) -> user32.UnregisterHotKey(null, id) }
            }
        }, "desknote-hotkeys").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
    }
}
