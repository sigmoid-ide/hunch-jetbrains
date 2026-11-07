package com.github.sigmoidide.hunch.model

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.WeakHashMap

/**
 * Searching the run table. A word is found anywhere in a run's id, hypotheses (earlier ones too),
 * conclusions, verdict, status, command or config, ignoring case. Filters narrow it down:
 *
 *   is:running, is:finished, is:crashed, is:stopped, is:lost   how it ended, or that it has not
 *   is:kept, is:dropped, is:unreviewed                          its verdict; unreviewed: ended, with none and no conclusion
 *   is:remote                                                    run on another machine by Ferry
 *   since:12h, since:7d, since:2w, since:today, since:2026-11-01 when it started
 *   from:a7f3                                                    that run and every run that came from it
 *   on:gpu-box, branch:main                                      where it ran, the git branch it started on
 *   val_loss<0.3, lr=3e-4, optimizer=adam                        a final metric or config value: <, <=, >, >=, =
 *
 * A run must match every word and filter. A leading - turns one around (-is:dropped), and commas
 * mean either (is:crashed,stopped). The same rules as Hunch for VS Code.
 */
object Search {
    class MenuItem(val filter: String, val label: String, val description: String? = null)

    /** The filters the filter menu offers; anything else is typed. */
    val MENU = listOf(
        MenuItem("is:unreviewed", "Not Reviewed Yet", "Ended, with no verdict or conclusion"),
        MenuItem("is:kept", "Kept"),
        MenuItem("-is:dropped", "Without Dropped Runs"),
        MenuItem("is:running", "Running"),
        MenuItem("is:crashed,stopped", "Crashed or Stopped"),
        MenuItem("is:remote", "Ran on Another Machine", "Copied home by Ferry"),
        MenuItem("since:1d", "Started in the Last 24 Hours"),
        MenuItem("since:7d", "Started in the Last 7 Days"),
    )

    const val HELP = "Filters: is:unreviewed, is:kept, since:7d, val_loss<0.3, lr=3e-4, from:a7f3, -is:dropped"

    private val IS: Map<String, (Run) -> Boolean> = mapOf(
        "running" to { run -> run.status == "running" },
        "finished" to { run -> run.status == "finished" },
        "crashed" to { run -> run.status == "crashed" },
        "stopped" to { run -> run.status == "killed" },
        "killed" to { run -> run.status == "killed" },
        "lost" to { run -> run.status == "lost" },
        "kept" to { run -> run.verdict == "keep" },
        "dropped" to { run -> run.verdict == "drop" },
        "unreviewed" to { run -> run.end != null && !run.trivial && run.verdict == null && run.conclusion == null },
        "remote" to { run -> run.remote != null },
    )

    private val QUALIFIER = Regex("^(is|since|from|on|branch):(.+)$", RegexOption.IGNORE_CASE)
    private val COMPARISON = Regex("^([A-Za-z_][\\w./-]*)(<=|>=|<|>|=)(.+)$")
    private val AGO = Regex("^(\\d+)([hdw])$")
    private val SEARCHED_NOTES = setOf("why", "note", "verdict")

    /** The runs that match a search: every word and filter in it. */
    fun filter(runs: List<Run>, query: String, root: Path, now: Instant = Instant.now()): List<Run> {
        val tests = tokens(query).map { token ->
            val negate = token.length > 1 && token.startsWith("-")
            val body = if (negate) token.drop(1) else token
            val word = body.lowercase()
            val test: (Run) -> Boolean = filterTest(body, runs, root, now) ?: { run -> word in read(run, root).text + "\n" + run.status }
            val matches: (Run) -> Boolean = { run -> test(run) != negate }
            matches
        }
        return runs.filter { run -> tests.all { it(run) } }
    }

    /** Whether the search has this filter, exactly as the menu writes it. */
    fun hasFilter(query: String, filter: String): Boolean = filter in tokens(query)

    /** The search with a filter added or taken out, leaving the rest as typed. */
    fun withFilter(query: String, filter: String, on: Boolean): String {
        val kept = tokens(query).filter { it != filter }
        return (if (on) kept + filter else kept).joinToString(" ")
    }

    private fun tokens(query: String) = query.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** A filter's test, or null when the token is a plain word (including filters that do not parse). */
    private fun filterTest(body: String, runs: List<Run>, root: Path, now: Instant): ((Run) -> Boolean)? {
        QUALIFIER.matchEntire(body)?.let { match ->
            val values = match.groupValues[2].lowercase().split(",").filter { it.isNotEmpty() }
            when (match.groupValues[1].lowercase()) {
                "is" -> {
                    val tests = values.mapNotNull { IS[it] }
                    if (tests.isNotEmpty() && tests.size == values.size) return { run -> tests.any { it(run) } }
                }
                "since" -> since(values.firstOrNull().orEmpty(), now)?.let { cutoff -> return { run -> !run.started.toInstant().isBefore(cutoff) } }
                "from" -> {
                    val family = descendants(runs, values)
                    return { run -> run.id in family }
                }
                "on" -> return { run ->
                    values.any { v -> listOfNotNull(run.remote?.machine, run.host["hostname"] as? String).any { v in it.lowercase() } }
                }
                "branch" -> return { run -> values.any { v -> v in run.line.orEmpty().lowercase() } }
            }
        }
        val comparison = COMPARISON.matchEntire(body) ?: return null
        val (name, op, wanted) = comparison.destructured
        return compare(name, op, wanted, root)
    }

    private fun since(text: String, now: Instant): Instant? {
        AGO.matchEntire(text)?.let { match ->
            val amount = match.groupValues[1].toLong()
            val hours = when (match.groupValues[2]) {
                "h" -> amount
                "d" -> amount * 24
                else -> amount * 24 * 7
            }
            return now.minus(Duration.ofHours(hours))
        }
        val zone = ZoneId.systemDefault()
        if (text == "today") return LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant()
        return try {
            LocalDate.parse(text).atStartOfDay(zone).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** The runs named (by the end of their id) and every run that came from them. */
    private fun descendants(runs: List<Run>, refs: List<String>): Set<String> {
        val family = runs.filter { run -> refs.any { run.id.lowercase().endsWith(it) } }.mapTo(HashSet()) { it.id }
        var grew = family.isNotEmpty()
        while (grew) {
            grew = false
            for (run in runs) {
                val parent = run.parent
                if (run.id !in family && parent != null && parent in family) {
                    family += run.id
                    grew = true
                }
            }
        }
        return family
    }

    /** A metric (its final value) or config value against a number, or = against what the table shows. */
    private fun compare(name: String, op: String, wanted: String, root: Path): (Run) -> Boolean {
        val number = wanted.toDoubleOrNull()?.takeIf { it.isFinite() }
        return { run ->
            val actual = lookup(run, name, root)
            if (op == "=") {
                actual != null && if (actual is Number && number != null) Format.value(actual) == Format.value(number)
                else Format.value(actual).lowercase() == wanted.lowercase()
            } else {
                val value = (actual as? Number)?.toDouble()
                value != null && number != null && when (op) {
                    "<" -> value < number
                    "<=" -> value <= number
                    ">" -> value > number
                    else -> value >= number
                }
            }
        }
    }

    private fun lookup(run: Run, name: String, root: Path): Any? {
        for (values in listOf(run.finalMetrics, read(run, root).config)) {
            if (name in values) return values[name]
            values.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return values[it] }
        }
        return null
    }

    private class Read(val text: String, val config: Map<String, Any?>)

    /** What a search looks through in each run, read once per run (a reload makes new runs). */
    private val cache = WeakHashMap<Run, Read>()

    private fun read(run: Run, root: Path): Read = cache.getOrPut(run) {
        val config = flatten(HunchStore.readJson(run.dir.resolve("config.json")).asMap()["values"].asMap())
        val text = listOfNotNull(
            run.id,
            run.meta["why"] as? String,
            *run.notes.filter { it["kind"] in SEARCHED_NOTES }.map { it["value"] as? String }.toTypedArray(),
            Format.command(run, root),
            *config.map { (key, value) -> "$key = ${Format.value(value)}" }.toTypedArray(),
        ).joinToString("\n").lowercase()
        Read(text, config)
    }

    /** Nested config as dotted keys, like hunch's flatten. */
    private fun flatten(values: Map<String, Any?>, prefix: String = ""): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in values) {
            val nested = value.asMap()
            if (value is Map<*, *> && nested.isNotEmpty()) out.putAll(flatten(nested, "$prefix$key.")) else out["$prefix$key"] = value
        }
        return out
    }
}
