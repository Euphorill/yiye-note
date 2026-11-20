package com.desknote.ui

import com.desknote.AppController
import java.awt.MouseInfo
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * 托盘图标只负责“在那儿”，菜单用 Compose 弹窗绘制，
 * 避免 AWT 原生菜单在部分系统上中文变方块。
 */
class TrayController(private val controller: AppController) {
    private var trayIcon: TrayIcon? = null

    fun install() {
        if (!SystemTray.isSupported()) return
        runCatching {
            val icon = TrayIcon(AppIcon.image(32), "一叶便签")
            icon.isImageAutoSize = true
            icon.addActionListener { controller.showAll() }
            icon.addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                        val point = MouseInfo.getPointerInfo()?.location ?: return
                        controller.openTrayMenu(point.x, point.y)
                    }
                }
            })
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
        }
    }

    fun remove() {
        trayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        trayIcon = null
    }
}
