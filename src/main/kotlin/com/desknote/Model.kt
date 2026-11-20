package com.desknote

import java.util.UUID

/** 窗口层级：智能置顶 / 置顶 / 普通 / 贴底（贴底会被其他窗口覆盖，不挡工作） */
enum class WindowLevel(val label: String) {
    TOP("置顶"),
    SMART("智能置顶"),
    NORMAL("普通"),
    BOTTOM("贴底");

    fun next(): WindowLevel = when (this) {
        TOP -> SMART
        SMART -> NORMAL
        NORMAL -> BOTTOM
        BOTTOM -> TOP
    }
}

/** 便签颜色与用途绑定：颜色只负责“这一类事”，切换时是有含义的 */
enum class NoteColor(val label: String, val argb: Long) {
    TODO("待办", 0xFFFFF1B8),
    WORK("工作", 0xFFD9EAFF),
    LIFE("生活", 0xFFD5F3E3),
    IDEA("灵感", 0xFFFFDAE3),
    OTHER("其他", 0xFFECEDF0);

    companion object {
        // 旧版 JSON 里存的是纯颜色名，读入时映射到新的语义色
        private val legacy = mapOf(
            "YELLOW" to TODO,
            "BLUE" to WORK,
            "MINT" to LIFE,
            "PINK" to IDEA,
            "GRAY" to OTHER
        )

        fun fromRaw(raw: String?): NoteColor {
            if (raw.isNullOrBlank()) return TODO
            entries.firstOrNull { it.name == raw }?.let { return it }
            return legacy[raw] ?: TODO
        }
    }
}

data class NoteState(
    val id: String = UUID.randomUUID().toString(),
    /** 自定义标题；留空时用正文首行作为标题 */
    val title: String = "",
    val text: String = "",
    /** 1~3 个标签，用于搜索和筛选 */
    val tags: List<String> = emptyList(),
    val x: Int = 420,
    val y: Int = 240,
    val width: Int = 280,
    val height: Int = 240,
    val level: WindowLevel = WindowLevel.NORMAL,
    val color: NoteColor = NoteColor.TODO,
    val visible: Boolean = true,
    /** 折叠后只保留标题条 */
    val collapsed: Boolean = false,
    /** 锁定后只读，避免误改 */
    val locked: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    /** 标题栏文字：标题为空则退回正文首行（待办行取去掉勾选框后的内容） */
    fun displayTitle(): String {
        val custom = title.trim()
        if (custom.isNotEmpty()) return custom.take(40)
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (first.isEmpty()) return "空白便签"
        val taskContent = parseTaskLine(first)?.second?.trim().orEmpty()
        return (if (taskContent.isNotEmpty()) taskContent else first).take(40)
    }
}

data class AppState(
    val notes: List<NoteState> = emptyList(),
    val autostart: Boolean = false,
    /** 局域网 REST 接口是否开启（默认关闭） */
    val apiEnabled: Boolean = false,
    /** 是否已创建过首次启动的欢迎便签（删除后不再重建） */
    val onboardingDone: Boolean = false
)

/** 托盘菜单的屏幕坐标（像素） */
data class TrayMenuState(val x: Int, val y: Int)

/** 让某张便签置前、聚焦并闪烁一次的请求 */
data class FocusRequest(val id: String, val tick: Int)

// 待办只用“行内标记”表达，正文仍然是一份纯文本：
// `- [ ] 内容`、`[x] 内容`，中文输入法下也可以写 `-（）内容` / `（√）内容`
private val taskLineRegex = Regex(
    "^\\s*(?:[-*•]\\s*)?(?:\\[([ xX√✓✔]?)\\]|［([ xX√✓✔]?)］|（([ xX√✓✔]?)）|\\(([ xX√✓✔]?)\\))(.*)$"
)

private val doneMarkers = setOf("x", "√", "✓", "✔")

private class TaskMatch(
    val done: Boolean,
    val content: String,
    val markerGroup: MatchGroup,
    /** 1: [] 2: ［］ 3: （） 4: () */
    val bracket: Int
)

private fun matchTask(line: String): TaskMatch? {
    val match = taskLineRegex.matchEntire(line) ?: return null
    for (index in 1..4) {
        val group = match.groups[index] ?: continue
        val done = group.value.lowercase() in doneMarkers
        return TaskMatch(done, match.groupValues[5].trimStart(), group, index)
    }
    return null
}

/** 解析一行待办：返回 是否完成 to 内容；不是待办行返回 null */
fun parseTaskLine(line: String): Pair<Boolean, String>? {
    val task = matchTask(line) ?: return null
    return task.done to task.content
}

/** 翻转一行待办的勾选状态，保留原本的前缀、缩进和括号风格 */
fun toggleTaskMarker(line: String): String? {
    val task = matchTask(line) ?: return null
    val start = task.markerGroup.range.first
    val end = task.markerGroup.range.last + 1
    val replacement = if (task.done) {
        // 半角方括号里保留一个空格，全角括号里直接留空更自然
        if (task.bracket == 1) " " else ""
    } else {
        "x"
    }
    return line.substring(0, start) + replacement + line.substring(end)
}

/** 已完成数 to 总数 */
fun taskProgress(text: String): Pair<Int, Int> {
    var done = 0
    var total = 0
    text.lineSequence().forEach { line ->
        parseTaskLine(line)?.let { (isDone, _) ->
            total++
            if (isDone) done++
        }
    }
    return done to total
}
