package com.github.sigmoidide.hunch.model

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A run's code snapshot: files.json lists each file's git blob SHA-1, and the blob
 * lives in .hunch/objects/<2 chars>/<38 chars> as a zlib-compressed git loose object.
 */
object Snapshot {
    data class Change(val path: String, val before: String?, val after: String?)

    /** Path to blob SHA, or null for runs recorded before snapshots (Hunch 0.1). */
    fun files(run: Run): Map<String, String>? {
        val manifest = HunchStore.readJson(run.dir.resolve("files.json")) ?: return null
        return manifest.asMap()["files"].asMap().mapValues { (_, entry) -> entry.asMap()["sha"].toString() }
    }

    fun read(store: HunchStore, sha: String): ByteArray? {
        val compressed = try {
            Files.readAllBytes(store.objects.resolve(sha.take(2)).resolve(sha.drop(2)))
        } catch (_: IOException) {
            return null
        }
        val raw = try {
            inflate(compressed)
        } catch (_: DataFormatException) {
            return null
        }
        val header = raw.indexOf(0.toByte())
        return if (header < 0) null else raw.copyOfRange(header + 1, raw.size)
    }

    /** Files that differ between two runs, as text; null when a run has no snapshot. */
    fun changes(store: HunchStore, before: Run, after: Run): List<Change>? {
        val old = files(before) ?: return null
        val new = files(after) ?: return null
        return (old.keys + new.keys).toSortedSet().mapNotNull { path ->
            val oldSha = old[path]
            val newSha = new[path]
            if (oldSha == newSha) null
            else Change(path, oldSha?.let { text(store, it) }, newSha?.let { text(store, it) })
        }
    }

    private fun text(store: HunchStore, sha: String): String =
        read(store, sha)?.toString(StandardCharsets.UTF_8) ?: "(missing from .hunch/objects)"

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 3)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw DataFormatException("truncated object")
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
