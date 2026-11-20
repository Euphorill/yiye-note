package com.desknote.platform

import java.io.File

/**
 * 开机自启：写入 HKCU\...\Run。
 * 打包成 exe 后直接注册 exe；开发态回退到 wscript 静默启动脚本，避免登录时闪控制台。
 */
object Autostart {

    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "DeskNote"

    private val appDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "DeskNote")

    fun isEnabled(): Boolean {
        val result = runReg("query", RUN_KEY, "/v", VALUE_NAME) ?: return false
        return result.first == 0
    }

    fun setEnabled(enabled: Boolean): Boolean {
        return if (enabled) {
            val target = launchTarget() ?: return false
            val result = runReg(
                "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", target, "/f"
            ) ?: return false
            result.first == 0
        } else {
            val result = runReg("delete", RUN_KEY, "/v", VALUE_NAME, "/f") ?: return false
            result.first == 0 || result.first == 1
        }
    }

    private fun launchTarget(): String? {
        val command = ProcessHandle.current().info().command().orElse(null)
        if (command != null && File(command).name.equals("YiyeNote.exe", ignoreCase = true)) {
            return "\"${File(command).absolutePath}\""
        }
        // 开发/未打包：用 VBS 静默拉起当前 classpath，避免 Run 项长度限制和控制台窗口
        return runCatching {
            appDir.mkdirs()
            val javaHome = System.getProperty("java.home")
            val javaw = File(javaHome, "bin/javaw.exe").absolutePath
            val classpath = System.getProperty("java.class.path")
            val commandLine = buildString {
                append('"').append(javaw).append('"')
                append(" -cp \"").append(classpath).append('"')
                append(" com.desknote.MainKt")
            }
            val escaped = commandLine.replace("\"", "\"\"")
            val vbs = File(appDir, "launch.vbs")
            vbs.writeText(
                "CreateObject(\"WScript.Shell\").Run \"$escaped\", 0, False\r\n",
                Charsets.UTF_8
            )
            "wscript.exe //B \"${vbs.absolutePath}\""
        }.getOrNull()
    }

    private fun runReg(vararg args: String): Pair<Int, String>? = runCatching {
        val process = ProcessBuilder("reg", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() to output
    }.getOrNull()
}
