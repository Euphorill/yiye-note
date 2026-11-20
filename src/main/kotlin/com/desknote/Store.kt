package com.desknote

import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 便签数据保存在 %APPDATA%\DeskNote\notes.json，纯本地，不依赖云 */
class Store {
    private val colorAdapter = object : TypeAdapter<NoteColor>() {
        override fun write(out: JsonWriter, value: NoteColor) {
            out.value(value.name)
        }

        override fun read(input: JsonReader): NoteColor {
            if (input.peek() == JsonToken.NULL) {
                input.nextNull()
                return NoteColor.TODO
            }
            return NoteColor.fromRaw(input.nextString())
        }
    }

    private val gson = GsonBuilder()
        .registerTypeAdapter(NoteColor::class.java, colorAdapter)
        .setPrettyPrinting()
        .create()

    private val dataDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "DeskNote")
    private val file = File(dataDir, "notes.json")
    private val backupDir = File(dataDir, "backups")

    fun load(): AppState = runCatching {
        if (!file.exists()) return@runCatching AppState()
        gson.fromJson(file.readText(Charsets.UTF_8), AppState::class.java) ?: AppState()
    }.getOrElse { AppState() }

    fun save(state: AppState) {
        runCatching {
            dataDir.mkdirs()
            val tmp = File(dataDir, "notes.json.tmp")
            tmp.writeText(gson.toJson(state), Charsets.UTF_8)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 局域网接口的访问令牌：首次生成后持久化，重启不变 */
    fun apiToken(): String {
        val tokenFile = File(dataDir, "api-token.txt")
        val saved = runCatching { tokenFile.readText(Charsets.UTF_8).trim() }.getOrNull().orEmpty()
        if (saved.length >= 16) return saved
        val token = SecureRandom().let { random ->
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it) }
        }
        runCatching {
            dataDir.mkdirs()
            tokenFile.writeText(token, Charsets.UTF_8)
        }
        return token
    }

    /** 每次启动留一份快照，最多保留 10 份，误删/写坏时可从这里找回 */
    fun backup() {
        runCatching {
            if (!file.exists()) return@runCatching
            backupDir.mkdirs()
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(LocalDateTime.now())
            val target = File(backupDir, "notes-$stamp.json")
            if (!target.exists()) file.copyTo(target)
            val all = backupDir.listFiles { f -> f.name.startsWith("notes-") }
                ?.sortedBy { it.name }
                ?: return@runCatching
            all.dropLast(MAX_BACKUPS).forEach { it.delete() }
        }
    }

    private companion object {
        const val MAX_BACKUPS = 10
    }
}
