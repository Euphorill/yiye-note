package com.desknote.platform

import com.desknote.WindowLevel
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.WinUser.MONITORINFO
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/**
 * 通过 Win32 控制窗口层级与窗口样式。
 * TOP：始终置顶；SMART：智能置顶（有全屏窗口时自动让位）；BOTTOM：压到所有普通窗口之下。
 */
object WindowLayer {

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
    private const val FLAGS = WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOACTIVATE
    private const val WS_EX_TOOLWINDOW = 0x00000080
    private const val WS_EX_APPWINDOW = 0x00040000

    // Windows 内建的 z-order 伪句柄
    private val HWND_NOTOPMOST = HWND(Pointer.createConstant(-2L))
    private val HWND_BOTTOM = HWND(Pointer.createConstant(1L))

    fun apply(window: Window, level: WindowLevel) {
        if (!isWindows) return
        runCatching {
            window.isAlwaysOnTop = level == WindowLevel.TOP || level == WindowLevel.SMART
            when (level) {
                WindowLevel.BOTTOM -> setInsertAfter(window, HWND_BOTTOM)
                WindowLevel.NORMAL -> setInsertAfter(window, HWND_NOTOPMOST)
                WindowLevel.TOP, WindowLevel.SMART -> Unit
            }
        }
    }

    /** 智能置顶：有全屏窗口时取消置顶，退出全屏后恢复 */
    fun applySmart(window: Window, fullscreenActive: Boolean) {
        if (!isWindows) return
        runCatching {
            val topmost = !fullscreenActive
            if (window.isAlwaysOnTop != topmost) window.isAlwaysOnTop = topmost
        }
    }

    fun pushToBottom(window: Window) {
        if (!isWindows) return
        runCatching {
            window.isAlwaysOnTop = false
            setInsertAfter(window, HWND_BOTTOM)
        }
    }

    /**
     * 把窗口标记为工具窗口（WS_EX_TOOLWINDOW）。
     * 这样任务栏的「显示桌面」不会把它最小化，也不会出现在任务栏和 Alt+Tab 里。
     */
    fun makeToolWindow(window: Window) {
        if (!isWindows) return
        runCatching {
            val hwnd = hwndOf(window)
            val ex = User32.INSTANCE.GetWindowLongPtr(hwnd, WinUser.GWL_EXSTYLE).toLong()
            val target = (ex or WS_EX_TOOLWINDOW.toLong()) and WS_EX_APPWINDOW.toLong().inv()
            if (target != ex) {
                User32.INSTANCE.SetWindowLongPtr(hwnd, WinUser.GWL_EXSTYLE, Pointer(target))
                User32.INSTANCE.SetWindowPos(
                    hwnd, null, 0, 0, 0, 0,
                    WinUser.SWP_FRAMECHANGED or WinUser.SWP_NOMOVE or
                        WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE
                )
            }
        }
    }

    /**
     * 当前是否有一个“铺满整块显示器”的可见窗口。
     * 不看焦点：全屏窗口失去焦点也算全屏，便签照样让位。
     */
    fun hasFullscreenWindow(): Boolean {
        if (!isWindows) return false
        val ownPid = ProcessHandle.current().pid().toInt()
        var found = false
        runCatching {
            User32.INSTANCE.EnumWindows(object : WNDENUMPROC {
                override fun callback(hwnd: HWND, data: Pointer?): Boolean {
                    if (found) return false
                    if (!User32.INSTANCE.IsWindowVisible(hwnd)) return true
                    val pid = IntByReference()
                    User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid)
                    if (pid.value == ownPid) return true
                    if (className(hwnd) in SHELL_CLASSES) return true
                    val rect = WinDef.RECT()
                    User32.INSTANCE.GetWindowRect(hwnd, rect)
                    if (rect.right - rect.left <= 0 || rect.bottom - rect.top <= 0) return true
                    val monitor = User32.INSTANCE.MonitorFromWindow(hwnd, User32.MONITOR_DEFAULTTONEAREST)
                        ?: return true
                    val info = MONITORINFO()
                    info.cbSize = info.size()
                    User32.INSTANCE.GetMonitorInfo(monitor, info)
                    val m = info.rcMonitor
                    if (rect.left <= m.left && rect.top <= m.top &&
                        rect.right >= m.right && rect.bottom >= m.bottom
                    ) {
                        found = true
                        return false
                    }
                    return true
                }
            }, null)
        }
        return found
    }

    // 桌面、任务栏、开始菜单等外壳窗口会铺满屏幕，但它们不算“全屏应用”
    private val SHELL_CLASSES = setOf(
        "Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd",
        "Windows.UI.Core.CoreWindow", "MultitaskingViewFrame", "XamlExplorerHostIslandWindow"
    )

    private fun className(hwnd: HWND): String {
        val buf = CharArray(256)
        val len = User32.INSTANCE.GetClassName(hwnd, buf, buf.size)
        return if (len > 0) String(buf, 0, len) else ""
    }

    private fun hwndOf(window: Window) = HWND(Native.getComponentPointer(window))

    private fun setInsertAfter(window: Window, target: HWND) {
        User32.INSTANCE.SetWindowPos(hwndOf(window), target, 0, 0, 0, 0, FLAGS)
    }
}
