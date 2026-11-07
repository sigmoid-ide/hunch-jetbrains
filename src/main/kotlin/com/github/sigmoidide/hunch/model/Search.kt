package com.github.sigmoidide.hunch.model

import java.nio.file.Path
import java.util.WeakHashMap

/**
 * Search in the run table: a run matches when it has every word somewhere in its id, hypotheses
 * (old ones too), conclusions, verdict, status, command or config, ignoring case.
 */
object Search {
    private val SEARCHED_NOTES = setOf("why", "note", "verdict")

    /** What a search looks through in each run, read once per run (a reload makes new runs). */
    private val texts = WeakHashMap<Run, String>()

    fun words(query: String): List<String> = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun matches(run: Run, words: List<String>, root: Path): Boolean {
        // The status can change without a reload: a quiet heartbeat turns running into lost.
        val text = texts.getOrPut(run) { text(run, root) } + "\n" + run.status
        return words.all { it in text }
    }

    private fun text(run: Run, root: Path): String {
        val config = HunchStore.readJson(run.dir.resolve("config.json")).asMap()["values"].asMap()
        return listOfNotNull(
            run.id,
            run.meta["why"] as? String,
            *run.notes.filter { it["kind"] in SEARCHED_NOTES }.map { it["value"] as? String }.toTypedArray(),
            Format.command(run, root),
            *config.map { (key, value) -> "$key = ${Format.value(value)}" }.toTypedArray(),
        ).joinToString("\n").lowercase()
    }
}
