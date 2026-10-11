package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(val file: File) : Closeable {
    private var closed = false
    fun reset(header: String) = synchronized(storageLock) {
        if (!closed) {
            runCatching {
                file.parentFile?.mkdirs()
                rotate()
                file.writeText("")
                append(header)
            }
        }
        Unit
    }

    /** An activity or process restart is a boundary, not a reason to evict the previous drive. */
    fun beginSession(header: String) = synchronized(storageLock) {
        if (closed) return@synchronized
        runCatching {
            file.parentFile?.mkdirs()
            file.createNewFile()
            append(header)
        }
        Unit
    }

    fun append(line: String) = synchronized(storageLock) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        runCatching {
            file.parentFile?.mkdirs()
            val bytes = (safe + "\n").toByteArray(Charsets.UTF_8)
            if (file.length() + bytes.size > MAX_BYTES) {
                rotate()
                file.writeText("")
            }
            file.appendBytes(bytes)
        }
        Unit
    }
    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        val archives = archiveNames(file)
        for (index in archives.lastIndex downTo 1) {
            val source = File(file.parentFile, archives[index - 1])
            val destination = File(file.parentFile, archives[index])
            if (source.exists()) move(source, destination)
        }
        move(file, File(file.parentFile, archives.first()))
    }

    private fun move(source: File, destination: File) {
        // Same-directory renames avoid copying several megabytes on a transport callback.
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            check(source.delete())
        }
    }

    override fun close() = synchronized(storageLock) { closed = true }
    companion object {
        const val MAX_BYTES = 512 * 1024L
        // Multiple activity instances and report exports may touch the same history.
        private val storageLock = Any()
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"

        private fun archiveNames(file: File): List<String> =
            if (file.name == "diplay.log") ARCHIVE_NAMES
            else ARCHIVE_NAMES.map { "${file.nameWithoutExtension}-$it" }

        /** Read only the bounded tail of each known file, and redact legacy contents again. */
        fun report(file: File, maxBytesPerFile: Long = MAX_BYTES): String = synchronized(storageLock) {
            require(maxBytesPerFile in 1..MAX_BYTES)
            buildString {
                for (name in archiveNames(file).reversed() + file.name) {
                    val history = File(file.parentFile, name)
                    if (!history.isFile) continue
                    appendLine("--- $name ---")
                    runCatching {
                        boundedTail(history, maxBytesPerFile).lineSequence().forEach { line ->
                            DiagnosticRedactor.redact(line)?.let { appendLine(it) }
                        }
                    }.onFailure { appendLine("Log history unavailable") }
                }
            }
        }

        private fun boundedTail(file: File, maxBytes: Long): String = RandomAccessFile(file, "r").use { source ->
            val start = (source.length() - maxBytes).coerceAtLeast(0)
            source.seek(start)
            val bytes = ByteArray((source.length() - start).toInt())
            source.readFully(bytes)
            // A clipped line may have lost its credential/payload prefix. Never export it.
            val offset = if (start == 0L) 0 else bytes.indexOf('\n'.code.toByte()).let {
                if (it < 0) bytes.size else it + 1
            }
            String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
        }
    }
}
