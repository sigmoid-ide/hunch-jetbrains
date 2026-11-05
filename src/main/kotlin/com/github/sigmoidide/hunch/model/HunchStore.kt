package com.github.sigmoidide.hunch.model

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/** A project's .hunch folder, read the way hunch itself reads it. Reads never write. */
class HunchStore(val root: Path) {
    val dir: Path = root.resolve(".hunch")
    val runsDir: Path = dir.resolve("runs")
    val objects: Path = dir.resolve("objects")

    fun exists(): Boolean = runsDir.isDirectory()

    fun config(): Map<String, Any?> = readJson(dir.resolve("config.json")).asMap()

    fun mainMetric(): String? = config()["main_metric"] as? String

    /** All runs, oldest first. Folders that are not runs (yet) are skipped. */
    fun runs(): List<Run> {
        if (!exists()) return emptyList()
        val folders = try {
            runsDir.listDirectoryEntries().filter { it.isDirectory() }
        } catch (_: IOException) {
            return emptyList()
        }
        return folders.mapNotNull(::load).sortedWith(compareBy<Run> { it.started }.thenBy { it.id })
    }

    /**
     * Changes whenever a run starts, ends, gets a note, or logs a metric while running.
     * Cheap: one directory listing and a few file sizes and times per run.
     */
    fun signature(): Long {
        if (!exists()) return 0
        var hash = 17L
        val folders = try {
            runsDir.listDirectoryEntries().sortedBy { it.fileName.toString() }
        } catch (_: IOException) {
            return 0
        }
        for (folder in folders) {
            hash = hash * 31 + folder.fileName.toString().hashCode()
            for (name in WATCHED_FILES) {
                val file = folder.resolve(name)
                hash = hash * 31 + (try { Files.size(file) } catch (_: IOException) { -1L })
            }
        }
        hash = hash * 31 + (try { Files.size(dir.resolve("config.json")) } catch (_: IOException) { -1L })
        // Whether a run without an end is still running depends on the clock.
        return hash * 31 + System.currentTimeMillis() / 60_000
    }

    /** Appends an annotation, exactly as `hunch note` and `hunch link` do. */
    fun annotate(run: Run, kind: String, value: Any?) {
        val event = linkedMapOf("at" to timestamp(), "kind" to kind, "value" to value)
        Files.writeString(
            run.dir.resolve("notes.jsonl"),
            Json.write(event) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }

    fun setMainMetric(name: String?) {
        Files.createDirectories(dir)
        val config = LinkedHashMap(config())
        if (name.isNullOrEmpty()) config.remove("main_metric") else config["main_metric"] = name
        val tmp = dir.resolve(".config.json.${ProcessHandle.current().pid()}.tmp")
        Files.writeString(tmp, Json.write(config) + "\n", StandardCharsets.UTF_8)
        Files.move(tmp, dir.resolve("config.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun load(folder: Path): Run? {
        val meta = readJson(folder.resolve("run.json")) as? Map<*, *> ?: return null
        if (meta["started_at"] !is String) return null
        @Suppress("UNCHECKED_CAST")
        meta as Map<String, Any?>
        val end = readJson(folder.resolve("end.json")) as? Map<String, Any?>
        val heartbeat = folder.resolve("heartbeat")
        val heartbeatAge = try {
            (System.currentTimeMillis() - Files.getLastModifiedTime(heartbeat).toMillis()) / 1000.0
        } catch (_: IOException) {
            null
        }
        return try {
            Run(
                dir = folder,
                meta = meta,
                end = end,
                notes = readJsonLines(folder.resolve("notes.jsonl")),
                heartbeatAge = heartbeatAge,
                liveRows = if (end == null) readJsonLines(folder.resolve("metrics.jsonl")) else emptyList(),
            )
        } catch (_: RuntimeException) {
            null // a run.json we cannot make sense of
        }
    }

    companion object {
        private val WATCHED_FILES = listOf("run.json", "end.json", "notes.jsonl", "metrics.jsonl")

        /**
         * The store for a project folder: the nearest folder with .hunch/runs from `start`
         * up to the git root, otherwise `start` itself.
         */
        fun find(start: Path): HunchStore {
            var folder: Path? = start
            while (folder != null) {
                if (folder.resolve(".hunch").resolve("runs").isDirectory()) return HunchStore(folder)
                if (folder.resolve(".git").exists()) break
                folder = folder.parent
            }
            return HunchStore(start)
        }

        fun readJson(file: Path): Any? = try {
            Json.parse(file.readText(StandardCharsets.UTF_8))
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        @Suppress("UNCHECKED_CAST")
        fun readJsonLines(file: Path): List<Map<String, Any?>> {
            val text = try {
                file.readText(StandardCharsets.UTF_8)
            } catch (_: IOException) {
                return emptyList()
            }
            // Split on \n only: values may contain U+2028 and other line separators.
            return text.split('\n').mapNotNull { line ->
                if (line.isBlank()) null
                else try {
                    Json.parse(line) as? Map<String, Any?>
                } catch (_: IllegalArgumentException) {
                    null // a last line cut short by a crash
                }
            }
        }

        fun timestamp(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS).toString()
    }
}
