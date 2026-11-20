package com.desknote.platform

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

/**
 * 单实例：用 instance.lock 的文件锁判定。
 * 第二个实例起不来时不杀已有实例，只写一个 show.request 文件，
 * 由已运行的实例通过 WatchService（事件驱动，不轮询）收到后显示全部便签。
 */
object SingleInstance {

    private val dataDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "DeskNote")
    private val requestFile = File(dataDir, "show.request")
    private var channel: FileChannel? = null

    fun acquire(): Boolean = try {
        dataDir.mkdirs()
        val ch = FileChannel.open(
            File(dataDir, "instance.lock").toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        )
        val lock = ch.tryLock()
        if (lock == null) {
            ch.close()
            false
        } else {
            channel = ch
            true
        }
    } catch (_: Exception) {
        // 判定不了锁时按“可以运行”处理，宁可多开也不要起不来
        true
    }

    fun requestShow() {
        runCatching {
            dataDir.mkdirs()
            requestFile.writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
        }
    }
}

/** 监听第二个实例留下的 show.request */
object ShowRequestWatcher {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var watchService: WatchService? = null

    fun start(onRequest: () -> Unit) {
        if (running) return
        val dataDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "DeskNote")
        if (!dataDir.exists()) dataDir.mkdirs()
        running = true
        thread = Thread({
            runCatching {
                val dir: Path = dataDir.toPath()
                val service = dir.fileSystem.newWatchService()
                watchService = service
                dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
                val requestName = "show.request"
                while (running) {
                    val key = service.poll(1, TimeUnit.SECONDS) ?: continue
                    var hit = false
                    key.pollEvents().forEach { event ->
                        if ((event.context() as? Path)?.toString() == requestName) hit = true
                    }
                    key.reset()
                    if (hit) {
                        File(dataDir, requestName).delete()
                        onRequest()
                    }
                }
            }
        }, "desknote-instance-watch").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { watchService?.close() }
        watchService = null
    }
}
