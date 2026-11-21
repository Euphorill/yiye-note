package com.desknote.platform

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * 局域网 REST 接口（默认关闭，开启时绑定 0.0.0.0，所有请求需带 X-Auth-Token）。
 *
 * GET    /notes                 返回全部便签
 * GET    /notes/{id}            返回单张便签
 * POST   /notes                 新建便签，body: {"text":"...","title":"..."}
 * POST   /notes/{id}/append     追加一行，body: {"text":"..."}
 * POST   /notes/{id}/toggle-task 切换待办勾选，body: {"line":0}
 * POST   /notes/{id}/line/{n}    改写第 n 行，body: {"text":"..."}
 * DELETE /notes/{id}/line/{n}    删除第 n 行
 * POST   /notes/{id}/title       修改标题，body: {"title":"..."}（空字符串即清空）
 * DELETE /notes/{id}            删除便签
 */
class LocalApi(
    private val token: String,
    private val handlers: Handlers
) {
    interface Handlers {
        fun listNotes(): List<Map<String, Any?>>
        fun getNote(id: String): Map<String, Any?>?
        fun createNote(text: String, title: String): String?
        fun appendLine(id: String, text: String): Boolean
        fun toggleTask(id: String, line: Int): Boolean
        fun setLine(id: String, line: Int, text: String): Boolean
        fun deleteLine(id: String, line: Int): Boolean
        fun setTitle(id: String, title: String): Boolean
        fun deleteNote(id: String): Boolean
    }

    private val gson = Gson()
    private var server: HttpServer? = null

    /** 返回 null 表示启动成功，否则是失败原因 */
    fun start(): String? = runCatching {
        val http = HttpServer.create(InetSocketAddress("0.0.0.0", PORT), 0)
        http.executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "desknote-api").apply { isDaemon = true }
        }
        http.createContext("/") { exchange -> handle(exchange) }
        http.start()
        server = http
        null
    }.getOrElse { it.message ?: "启动失败" }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestHeaders.getFirst(TOKEN_HEADER) != token) {
                respond(exchange, 401, mapOf("error" to "unauthorized"))
                return
            }
            val path = exchange.requestURI.path.trimEnd('/').ifEmpty { "/" }
            val method = exchange.requestMethod.uppercase()

            when {
                path == "/notes" && method == "GET" ->
                    respond(exchange, 200, mapOf("notes" to handlers.listNotes()))

                path == "/notes" && method == "POST" -> {
                    val body = readBody(exchange)
                    val text = body?.get("text")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val title = body?.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    if (text.isBlank() && title.isBlank()) {
                        respond(exchange, 400, mapOf("error" to "text/title 不能同时为空"))
                    } else {
                        val id = handlers.createNote(text, title)
                        if (id == null) respond(exchange, 500, mapOf("error" to "创建失败"))
                        else respond(exchange, 201, mapOf("id" to id))
                    }
                }

                path.startsWith("/notes/") -> {
                    val rest = path.removePrefix("/notes/")
                    val slash = rest.indexOf('/')
                    val id = if (slash >= 0) rest.substring(0, slash) else rest
                    val action = if (slash >= 0) rest.substring(slash + 1) else ""
                    if (id.isBlank()) {
                        respond(exchange, 404, mapOf("error" to "not found"))
                        return
                    }
                    when {
                        action.isEmpty() && method == "GET" -> {
                            val note = handlers.getNote(id)
                            if (note == null) respond(exchange, 404, mapOf("error" to "not found"))
                            else respond(exchange, 200, note)
                        }

                        action.isEmpty() && method == "DELETE" -> {
                            if (handlers.deleteNote(id)) respond(exchange, 200, mapOf("ok" to true))
                            else respond(exchange, 404, mapOf("error" to "not found"))
                        }

                        action == "append" && method == "POST" -> {
                            val text = readBody(exchange)?.get("text")?.takeIf { !it.isJsonNull }?.asString
                            if (text.isNullOrBlank()) {
                                respond(exchange, 400, mapOf("error" to "text 不能为空"))
                            } else if (handlers.appendLine(id, text)) {
                                respond(exchange, 200, mapOf("ok" to true))
                            } else {
                                respond(exchange, 404, mapOf("error" to "not found"))
                            }
                        }

                        action == "toggle-task" && method == "POST" -> {
                            val line = readBody(exchange)?.get("line")?.takeIf { !it.isJsonNull }?.asInt
                            if (line == null || line < 0) {
                                respond(exchange, 400, mapOf("error" to "line 必须是非负整数"))
                            } else if (handlers.toggleTask(id, line)) {
                                respond(exchange, 200, mapOf("ok" to true))
                            } else {
                                respond(exchange, 404, mapOf("error" to "not found 或该行不是待办"))
                            }
                        }

                        action == "title" && method == "POST" -> {
                            val title = readBody(exchange)?.get("title")?.takeIf { !it.isJsonNull }?.asString
                            if (title == null) {
                                respond(exchange, 400, mapOf("error" to "缺少 title（清空可传空字符串）"))
                            } else if (handlers.setTitle(id, title)) {
                                respond(exchange, 200, mapOf("ok" to true))
                            } else {
                                respond(exchange, 404, mapOf("error" to "not found"))
                            }
                        }

                        action.startsWith("line/") -> {
                            val line = action.removePrefix("line/").toIntOrNull()
                            when {
                                line == null || line < 0 ->
                                    respond(exchange, 400, mapOf("error" to "行号必须是非负整数"))

                                method == "POST" -> {
                                    val text = readBody(exchange)?.get("text")?.takeIf { !it.isJsonNull }?.asString
                                    when {
                                        text == null ->
                                            respond(exchange, 400, mapOf("error" to "text 不能为空"))
                                        text.contains('\n') || text.contains('\r') ->
                                            respond(exchange, 400, mapOf("error" to "text 只能包含单行内容"))
                                        handlers.setLine(id, line, text) ->
                                            respond(exchange, 200, mapOf("ok" to true))
                                        else ->
                                            respond(exchange, 404, mapOf("error" to "not found 或行号越界"))
                                    }
                                }

                                method == "DELETE" -> {
                                    if (handlers.deleteLine(id, line)) {
                                        respond(exchange, 200, mapOf("ok" to true))
                                    } else {
                                        respond(exchange, 404, mapOf("error" to "not found 或行号越界"))
                                    }
                                }

                                else -> respond(exchange, 404, mapOf("error" to "not found"))
                            }
                        }

                        else -> respond(exchange, 404, mapOf("error" to "not found"))
                    }
                }

                else -> respond(exchange, 404, mapOf("error" to "not found"))
            }
        } catch (t: Throwable) {
            runCatching { respond(exchange, 500, mapOf("error" to (t.message ?: "server error"))) }
        } finally {
            exchange.close()
        }
    }

    private fun readBody(exchange: HttpExchange): JsonObject? = runCatching {
        val raw = exchange.requestBody.readBytes().toString(Charsets.UTF_8).trim()
        if (raw.isEmpty()) JsonObject() else JsonParser.parseString(raw).asJsonObject
    }.getOrNull()

    private fun respond(exchange: HttpExchange, code: Int, payload: Any) {
        val bytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val PORT = 17845
        const val TOKEN_HEADER = "X-Auth-Token"

        /** 局域网 IPv4 地址，用来在提示里拼访问地址 */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
