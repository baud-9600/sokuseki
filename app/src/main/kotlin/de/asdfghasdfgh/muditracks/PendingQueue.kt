package de.asdfghasdfgh.muditracks

import java.io.File

/**
 * Small, crash-safe FIFO of unsent OwnTracks payloads. Each payload is one line,
 * so a partially written file can be recovered line by line.
 */
class PendingQueue(
    private val file: File,
    private val maxEntries: Int = 500,
) {
    private val entries = ArrayDeque<String>()

    init {
        load()
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun add(payload: String) {
        while (entries.size >= maxEntries) {
            entries.removeFirst()
        }
        entries.addLast(payload)
        persist()
    }

    @Synchronized
    fun peek(): String? = entries.firstOrNull()

    @Synchronized
    fun removeFirst() {
        if (entries.isNotEmpty()) {
            entries.removeFirst()
            persist()
        }
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    private fun load() {
        if (!file.isFile) return
        runCatching {
            file.readLines()
                .map(String::trim)
                .filter { it.startsWith("{") && it.endsWith("}") }
                .takeLast(maxEntries)
                .forEach(entries::addLast)
        }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(entries.joinToString(separator = "\n", postfix = if (entries.isEmpty()) "" else "\n"))
            if (file.exists() && !file.delete()) {
                // Fall through: writeText below still replaces the content.
            }
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
