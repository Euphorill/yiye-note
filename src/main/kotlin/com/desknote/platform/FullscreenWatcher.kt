package com.desknote.platform

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LONG
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.platform.win32.WinUser.MSG
import com.sun.jna.platform.win32.WinUser.WinEventProc
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 监听“当前有没有全屏窗口”。
 *
 * 用 WinEvent 钩子驱动，不做定时轮询：没有事件时线程只在消息队列上小睡，开销可忽略，响应是即时的。
 * 判定本身不看焦点，全屏窗口失去焦点也算全屏。
 *
 * 事件来源：
 *  - EVENT_SYSTEM_FOREGROUND：切换前台
 *  - EVENT_OBJECT_LOCATIONCHANGE：窗口被移出/还原成整屏（F11、视频全屏）
 *  - EVENT_OBJECT_SHOW/HIDE：全屏窗口出现或消失（有些窗口进入全屏不抢焦点，只能靠这个兜住）
 */
object FullscreenWatcher {

    private const val EVENT_SYSTEM_FOREGROUND = 0x0003
    private const val EVENT_OBJECT_LOCATIONCHANGE = 0x800B
    private const val EVENT_OBJECT_SHOW = 0x8002
    private const val EVENT_OBJECT_HIDE = 0x8003
    private const val WINEVENT_OUTOFCONTEXT = 0x0000
    private const val WINEVENT_SKIPOWNPROCESS = 0x0002
    private const val PM_REMOVE = 0x0001
    private const val THROTTLE_MS = 120L

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile private var fullscreen = false
    @Volatile private var running = false
    private var pump: Thread? = null
    private var foregroundProc: WinEventProc? = null
    private var locationProc: WinEventProc? = null
    private var showProc: WinEventProc? = null
    private var lastCheck = 0L

    fun addListener(listener: (Boolean) -> Unit) {
        start()
        listeners.add(listener)
        listener(fullscreen)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    fun start() {
        if (!isWindows || running) return
        running = true
        pump = Thread(::runPump, "desknote-fs-watch").apply {
            isDaemon = true
            start()
        }
    }

    private fun runPump() {
        // 前台切换：立即重新判定
        val fgProc = object : WinEventProc {
            override fun callback(
                hook: HANDLE?,
                event: DWORD?,
                hwnd: HWND?,
                idObject: LONG?,
                idChild: LONG?,
                eventThread: DWORD?,
                eventTime: DWORD?
            ) {
                refresh()
            }
        }
        // 位置/尺寸变化（F11、视频全屏）以及窗口出现/消失：限流后重新判定
        val throttledProc = object : WinEventProc {
            override fun callback(
                hook: HANDLE?,
                event: DWORD?,
                hwnd: HWND?,
                idObject: LONG?,
                idChild: LONG?,
                eventThread: DWORD?,
                eventTime: DWORD?
            ) {
                val now = System.currentTimeMillis()
                if (now - lastCheck < THROTTLE_MS) return
                lastCheck = now
                refresh()
            }
        }
        // 窗口出现/消失：不等限流，直接判定（全屏窗口常常是先摆好位置再显示，且不抢焦点）
        val showProc = object : WinEventProc {
            override fun callback(
                hook: HANDLE?,
                event: DWORD?,
                hwnd: HWND?,
                idObject: LONG?,
                idChild: LONG?,
                eventThread: DWORD?,
                eventTime: DWORD?
            ) {
                refresh()
            }
        }
        foregroundProc = fgProc
        locationProc = throttledProc
        this.showProc = showProc
        User32.INSTANCE.SetWinEventHook(
            EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_FOREGROUND, null, fgProc, 0, 0,
            WINEVENT_OUTOFCONTEXT or WINEVENT_SKIPOWNPROCESS
        )
        User32.INSTANCE.SetWinEventHook(
            EVENT_OBJECT_LOCATIONCHANGE, EVENT_OBJECT_LOCATIONCHANGE, null, throttledProc, 0, 0,
            WINEVENT_OUTOFCONTEXT or WINEVENT_SKIPOWNPROCESS
        )
        User32.INSTANCE.SetWinEventHook(
            EVENT_OBJECT_SHOW, EVENT_OBJECT_HIDE, null, showProc, 0, 0,
            WINEVENT_OUTOFCONTEXT or WINEVENT_SKIPOWNPROCESS
        )
        refresh()

        val msg = MSG()
        while (running) {
            if (User32.INSTANCE.PeekMessage(msg, null, 0, 0, PM_REMOVE)) {
                User32.INSTANCE.TranslateMessage(msg)
                User32.INSTANCE.DispatchMessage(msg)
            } else {
                try {
                    Thread.sleep(15)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun refresh() {
        val value = WindowLayer.hasFullscreenWindow()
        if (value == fullscreen) return
        fullscreen = value
        listeners.forEach { runCatching { it(value) } }
    }
}
