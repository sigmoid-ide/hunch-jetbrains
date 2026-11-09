package com.github.sigmoidide.hunch.model

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** Text formatting shared with the `hunch` command line, so both read the same. */
object Format {
    /** `python train.py --lr 3e-4` instead of the interpreter's full path and absolute script paths. */
    fun command(run: Run, root: Path): String = run.command.mapIndexed { index, part ->
        val path = try {
            Paths.get(part)
        } catch (_: InvalidPathException) {
            null
        }
        when {
            path == null || !path.isAbsolute -> part
            index == 0 -> path.fileName.toString().removeSuffix(".exe")
            path.startsWith(root) -> root.relativize(path).toString()
            else -> part
        }
    }.joinToString(" ")

    /** The shortest unique ending of each id: 4 characters, then longer. */
    fun shortIds(ids: List<String>): Map<String, String> {
        val lengths = listOf(4, 6, 11)
        val counts = lengths.associateWith { n -> ids.groupingBy { it.takeLast(n) }.eachCount() }
        return ids.associateWith { id ->
            lengths.firstOrNull { n -> counts.getValue(n)[id.takeLast(n)] == 1 }?.let { id.takeLast(it) } ?: id
        }
    }

    /** Like Python's f"{value:.4g}" for floats, so numbers match `hunch log`. */
    fun value(value: Any?): String = when (value) {
        null -> "-"
        is Double -> when {
            value.isNaN() -> "nan"
            value == Double.POSITIVE_INFINITY -> "inf"
            value == Double.NEGATIVE_INFINITY -> "-inf"
            value == 0.0 -> "0"
            else -> {
                val rounded = BigDecimal(value).round(MathContext(4)).stripTrailingZeros()
                if (rounded.abs() >= BigDecimal("1e16") || rounded.abs() < BigDecimal("1e-4")) {
                    String.format("%.3e", value)
                } else {
                    rounded.toPlainString()
                }
            }
        }
        is Float -> value(value.toDouble())
        else -> value.toString()
    }

    fun duration(seconds: Double?): String {
        if (seconds == null) return "?"
        var s = Math.round(seconds)
        if (s < 60) return "${s}s"
        var m = s / 60
        s %= 60
        if (m < 60) return "${m}m ${s}s"
        var h = m / 60
        m %= 60
        if (h < 48) return "${h}h ${m}m"
        val d = h / 24
        h %= 24
        return "${d}d ${h}h"
    }

    /** "vast-59870289 (Vast.ai, PL)": the machine, and for a rented one who rented it out and the country it was in. */
    fun machine(remote: Remote): String {
        val where = listOfNotNull(if (remote.provider == "vast") "Vast.ai" else remote.provider, remote.country)
            .filter { it.isNotEmpty() }.joinToString(", ")
        return if (where.isEmpty()) remote.machine else "${remote.machine} ($where)"
    }

    /** "about $0.02" for Ferry's estimate, "$0.02" once billed, and "under a cent" for less, as Ferry says it. */
    fun cost(cost: Cost): String {
        if (cost.dollars < 0.01) return "under a cent"
        val amount = "\$" + BigDecimal(cost.dollars).setScale(2, RoundingMode.HALF_UP).toPlainString()
        return if (cost.billed) amount else "about $amount"
    }

    /** What `hunch log` shows in the result column. */
    fun result(run: Run, mainMetric: String?): String {
        val key = run.mainMetric(mainMetric)
        val metric = key?.let { k -> run.finalMetrics[k]?.let { "$k ${value(it)}" } }.orEmpty()
        val state = when (run.status) {
            "finished" -> return metric
            "crashed" -> run.exitCode?.let { "crashed, exit $it" } ?: "crashed"
            "running" -> "running ${duration((System.currentTimeMillis() / 1000.0) - run.started.toEpochSecond())}" +
                (run.remote?.let { " on ${it.machine}" } ?: "")
            else -> run.status
        }
        return if (metric.isEmpty()) state else "$state; $metric"
    }
}
