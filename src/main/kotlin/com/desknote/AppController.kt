package com.desknote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.desknote.platform.Autostart
import com.desknote.platform.Hotkeys
import com.desknote.platform.LocalApi
import java.awt.EventQueue
import java.awt.Toolkit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.sqrt

class AppController(private val store: Store = Store()) {

    val notes = mutableStateListOf<NoteState>()

    var autostartEnabled by mutableStateOf(false)
        private set

    var trayMenu by mutableStateOf<TrayMenuState?>(null)
        private set

    var searchOpen by mutableStateOf(false)
        private set

    var focusRequest by mutableStateOf<FocusRequest?>(null)
        private set

    /** 局域网接口的固定令牌，首次启动生成并持久化 */
    val apiToken: String = store.apiToken()

    var apiEnabled by mutableStateOf(false)
        private set

    /** 接口启动失败原因（例如端口被占用） */
    var apiError by mutableStateOf<String?>(null)
        private set

    private var api: LocalApi? = null

    private val apiHandlers = object : LocalApi.Handlers {
        override fun listNotes(): List<Map<String, Any?>> = onEdt { notes.map { it.toApiMap() } }

        override fun getNote(id: String): Map<String, Any?>? =
            onEdt { notes.firstOrNull { it.id == id }?.toApiMap() }

        override fun createNote(text: String, title: String): String? = onEdt {
            val screen = Toolkit.getDefaultToolkit().screenSize
            val offset = (notes.size % 6) * 28
            val now = System.currentTimeMillis()
            val note = NoteState(
                title = title,
                text = text,
                x = (420 + offset).coerceAtLeast(0).coerceAtMost(screen.width - 120),
                y = (240 + offset).coerceAtLeast(0).coerceAtMost(screen.height - 120),
                visible = true,
                createdAt = now,
                updatedAt = now
            )
            notes.add(note)
            saveSoon()
            note.id
        }

        override fun appendLine(id: String, text: String): Boolean = onEdt {
            val index = notes.indexOfFirst { it.id == id }
            if (index < 0) {
                false
            } else {
                val note = notes[index]
                val addition = text.trim('\n')
                val updated = if (note.text.isBlank()) addition else note.text.trimEnd('\n') + "\n" + addition
                notes[index] = note.copy(text = updated, updatedAt = System.currentTimeMillis())
                saveSoon()
                true
            }
        }

        override fun toggleTask(id: String, line: Int): Boolean = onEdt {
            val note = notes.firstOrNull { it.id == id }
            val target = note?.text?.split('\n')?.getOrNull(line)
            if (note == null || target == null || parseTaskLine(target) == null) {
                false
            } else {
                this@AppController.toggleTask(id, line)
                true
            }
        }

        override fun deleteNote(id: String): Boolean = onEdt {
            val existed = notes.any { it.id == id }
            if (existed) delete(id)
            existed
        }
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "desknote-save").apply { isDaemon = true }
    }
    private var pendingSave: ScheduledFuture<*>? = null

    /** 欢迎便签是否已创建过；删除后不再重建 */
    private var onboardingDone = false

    init {
        store.backup()
        val state = store.load()
        val now = System.currentTimeMillis()
        notes.addAll(state.notes.map { note ->
            // 旧数据没有时间戳，补一个，避免界面显示 1970
            if (note.createdAt <= 0L) note.copy(createdAt = now, updatedAt = if (note.updatedAt > 0L) note.updatedAt else now) else note
        })
        autostartEnabled = Autostart.isEnabled()
        if (state.apiEnabled && startApi()) apiEnabled = true

        onboardingDone = state.onboardingDone
        when {
            notes.isEmpty() -> {
                notes.add(if (onboardingDone) blankNote(now) else welcomeNote(now))
                onboardingDone = true
                saveNow()
            }
            // 已有便签的旧版本升级用户：直接视为已完成引导，避免突然多出一张欢迎便签
            !onboardingDone -> {
                onboardingDone = true
                saveNow()
            }
        }
    }

    private fun welcomeNote(now: Long): NoteState {
        val screen = Toolkit.getDefaultToolkit().screenSize
        val width = 420
        return NoteState(
            title = "欢迎使用一叶便签",
            text = "- [ ] 点一下左边的方框，试试点选这条待办\n" +
                "Ctrl+Alt+N 新建 · Ctrl+Alt+H 显示/隐藏全部\n" +
                "我在任务栏右下角的托盘里，右键可以新建、搜索和设置",
            tags = listOf("入门"),
            x = ((screen.width - width) / 2).coerceAtLeast(0),
            y = (screen.height / 4).coerceAtLeast(0),
            width = width,
            height = 367,
            createdAt = now,
            updatedAt = now
        )
    }

    /** 引导完成后便签被删空时新建一张空白便签，避免“零窗口”导致应用退出 */
    private fun blankNote(now: Long): NoteState {
        val screen = Toolkit.getDefaultToolkit().screenSize
        return NoteState(
            x = ((screen.width - 420) / 2).coerceAtLeast(0),
            y = (screen.height / 4).coerceAtLeast(0),
            width = 420,
            height = 367,
            visible = true,
            createdAt = now,
            updatedAt = now
        )
    }

    fun newNote() {
        val screen = Toolkit.getDefaultToolkit().screenSize
        val offset = (notes.size % 6) * 28
        val x = (420 + offset).coerceAtLeast(0).coerceAtMost(screen.width - 120)
        val y = (240 + offset).coerceAtLeast(0).coerceAtMost(screen.height - 120)
        val now = System.currentTimeMillis()
        notes.add(NoteState(x = x, y = y, visible = true, createdAt = now, updatedAt = now))
        saveSoon()
    }

    fun updateText(id: String, text: String) {
        mutate(id) { it.copy(text = text, updatedAt = System.currentTimeMillis()) }
    }

    fun updateTitle(id: String, title: String) {
        mutate(id) { it.copy(title = title, updatedAt = System.currentTimeMillis()) }
    }

    fun updateTags(id: String, tags: List<String>) {
        mutate(id) { it.copy(tags = tags, updatedAt = System.currentTimeMillis()) }
    }

    /** 点击阅读态里的勾选框：只改写那一行的标记，正文仍是同一份文本 */
    fun toggleTask(id: String, lineIndex: Int) {
        mutate(id) { note ->
            val lines = note.text.split('\n')
            val toggled = lines.getOrNull(lineIndex)?.let { toggleTaskMarker(it) }
            if (toggled == null) {
                note
            } else {
                val updated = lines.toMutableList().apply { this[lineIndex] = toggled }
                note.copy(text = updated.joinToString("\n"), updatedAt = System.currentTimeMillis())
            }
        }
    }

    fun updateBounds(id: String, x: Int, y: Int, width: Int, height: Int?) {
        mutate(id) {
            it.copy(
                x = x,
                y = y,
                width = width.coerceAtLeast(200),
                height = height?.coerceAtLeast(120) ?: it.height
            )
        }
    }

    fun cycleLevel(id: String) {
        mutate(id) { it.copy(level = it.level.next()) }
    }

    fun setColor(id: String, color: NoteColor) {
        mutate(id) { it.copy(color = color) }
    }

    fun setLocked(id: String, locked: Boolean) {
        mutate(id) { it.copy(locked = locked) }
    }

    fun setCollapsed(id: String, collapsed: Boolean) {
        mutate(id) { it.copy(collapsed = collapsed, visible = true) }
    }

    fun toggleVisible(id: String) {
        mutate(id) { it.copy(visible = !it.visible) }
    }

    fun hide(id: String) {
        mutate(id) { it.copy(visible = false) }
    }

    fun delete(id: String) {
        notes.removeAll { it.id == id }
        saveSoon()
    }

    fun showAll() {
        for (i in notes.indices) {
            notes[i] = notes[i].copy(visible = true)
        }
        saveSoon()
    }

    fun hideAll() {
        for (i in notes.indices) {
            notes[i] = notes[i].copy(visible = false)
        }
        saveSoon()
    }

    fun toggleAll() {
        if (notes.any { it.visible }) hideAll() else showAll()
    }

    /** 把某张便签显示出来、置前并闪一下，方便在便签堆里定位 */
    fun focusNote(id: String) {
        val index = notes.indexOfFirst { it.id == id }
        if (index < 0) return
        notes[index] = notes[index].copy(visible = true)
        val tick = (focusRequest?.tick ?: 0) + 1
        focusRequest = FocusRequest(id, tick)
        saveSoon()
    }

    fun focusNext(step: Int) {
        if (notes.isEmpty()) return
        val current = focusRequest?.id
        val index = notes.indexOfFirst { it.id == current }
        val next = if (index < 0) 0 else ((index + step) % notes.size + notes.size) % notes.size
        focusNote(notes[next].id)
    }

    fun search(query: String): List<NoteState> {
        val q = query.trim()
        if (q.isEmpty()) return notes
        return notes.filter { note ->
            note.displayTitle().contains(q, ignoreCase = true) ||
                note.text.contains(q, ignoreCase = true) ||
                note.tags.any { it.contains(q, ignoreCase = true) }
        }
    }

    /** 网格排列：便签互相遮挡时一键铺开 */
    fun arrangeGrid() {
        if (notes.isEmpty()) return
        val screen = Toolkit.getDefaultToolkit().screenSize
        val cols = ceil(sqrt(notes.size.toDouble())).toInt().coerceAtLeast(1)
        val gapX = 16
        val gapY = 16
        val cellW = notes.maxOf { it.width } + gapX
        val cellH = notes.maxOf { it.height } + gapY
        val rows = ceil(notes.size.toDouble() / cols).toInt()
        val startX = ((screen.width - cols * cellW) / 2).coerceAtLeast(16)
        val startY = ((screen.height - rows * cellH) / 2).coerceAtLeast(16)
        notes.forEachIndexed { i, note ->
            notes[i] = note.copy(
                x = startX + (i % cols) * cellW,
                y = startY + (i / cols) * cellH,
                visible = true,
                collapsed = false
            )
        }
        saveSoon()
    }

    /** 右上角堆叠：像便签堆一样错位叠在屏幕右侧 */
    fun arrangeStack() {
        if (notes.isEmpty()) return
        val screen = Toolkit.getDefaultToolkit().screenSize
        notes.forEachIndexed { i, note ->
            notes[i] = note.copy(
                x = (screen.width - note.width - 24 - i * 22).coerceAtLeast(16),
                y = 60 + i * 18,
                visible = true
            )
        }
        saveSoon()
    }

    fun openSearch() {
        searchOpen = true
    }

    fun closeSearch() {
        searchOpen = false
    }

    fun toggleSearch() {
        searchOpen = !searchOpen
    }

    fun handleHotkey(action: Int) {
        when (action) {
            Hotkeys.NEW -> newNote()
            Hotkeys.TOGGLE_ALL -> toggleAll()
            Hotkeys.NEXT -> focusNext(1)
            Hotkeys.PREV -> focusNext(-1)
            Hotkeys.SEARCH -> toggleSearch()
        }
    }

    fun toggleAutostart() {
        val target = !autostartEnabled
        val ok = Autostart.setEnabled(target)
        autostartEnabled = if (ok) target else Autostart.isEnabled()
        saveSoon()
    }

    /** 局域网接口开关：开启后绑定 0.0.0.0，所有请求需带 X-Auth-Token */
    fun toggleApi() {
        if (apiEnabled) {
            api?.stop()
            api = null
            apiEnabled = false
            apiError = null
        } else if (startApi()) {
            apiEnabled = true
            apiError = null
        }
        saveSoon()
    }

    /** 复制给 agent/脚本用的地址与令牌 */
    fun apiInfo(): String {
        val host = LocalApi.lanAddress() ?: "127.0.0.1"
        return "http://$host:${LocalApi.PORT}\n${LocalApi.TOKEN_HEADER}: $apiToken"
    }

    private fun startApi(): Boolean {
        if (api != null) return true
        val candidate = LocalApi(apiToken, apiHandlers)
        val error = candidate.start()
        return if (error == null) {
            api = candidate
            true
        } else {
            apiError = error
            false
        }
    }

    /** 接口线程里的读写都切到 EDT，避免在非 UI 线程改动 Compose 状态 */
    private fun <T> onEdt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: T? = null
        var failure: Throwable? = null
        EventQueue.invokeAndWait {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun NoteState.toApiMap(): Map<String, Any?> {
        val progress = taskProgress(text)
        return mapOf(
            "id" to id,
            "title" to displayTitle(),
            "customTitle" to title,
            "text" to text,
            "tags" to tags,
            "color" to color.name,
            "colorLabel" to color.label,
            "level" to level.name,
            "visible" to visible,
            "locked" to locked,
            "collapsed" to collapsed,
            "createdAt" to createdAt,
            "updatedAt" to updatedAt,
            "progress" to mapOf("done" to progress.first, "total" to progress.second)
        )
    }

    fun openTrayMenu(x: Int, y: Int) {
        trayMenu = TrayMenuState(x, y)
    }

    fun closeTrayMenu() {
        trayMenu = null
    }

    fun saveSoon() {
        val snapshot = snapshot()
        pendingSave?.cancel(false)
        pendingSave = scheduler.schedule({ store.save(snapshot) }, 400, TimeUnit.MILLISECONDS)
    }

    fun saveNow() {
        pendingSave?.cancel(false)
        store.save(snapshot())
    }

    private inline fun mutate(id: String, block: (NoteState) -> NoteState) {
        val index = notes.indexOfFirst { it.id == id }
        if (index < 0) return
        notes[index] = block(notes[index])
        saveSoon()
    }

    private fun snapshot() = AppState(
        notes = notes.map { it.copy() },
        autostart = autostartEnabled,
        apiEnabled = apiEnabled,
        onboardingDone = onboardingDone
    )
}
