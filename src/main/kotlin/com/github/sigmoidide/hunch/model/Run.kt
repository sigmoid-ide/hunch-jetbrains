package com.github.sigmoidide.hunch.model

import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * Where a run that Ferry copies home from another machine ran (remote.json, which only this computer has).
 * On a machine Ferry rented, also who from, such as "vast", and the country it was in, such as "PL".
 */
data class Remote(val machine: String, val syncedAt: OffsetDateTime?, val provider: String? = null, val country: String? = null)

/** What a run on a rented machine cost, in US dollars (cost.json, which Ferry writes once the run is home). */
data class Cost(
    val dollars: Double,
    /** The provider's own charge, rather than Ferry's estimate. */
    val billed: Boolean,
)

/**
 * One run folder, read back. Mirrors hunch's RunRecord (store.py), following the
 * on-disk format in hunch's docs/format.md.
 */
class Run(
    val dir: Path,
    val meta: Map<String, Any?>,
    val end: Map<String, Any?>?,
    val notes: List<Map<String, Any?>>,
    /** Seconds since the heartbeat file last changed, or null without one. */
    private val heartbeatAge: Double?,
    /** metrics.jsonl rows; only read for runs still in progress. */
    private val liveRows: List<Map<String, Any?>>,
    val remote: Remote? = null,
    val cost: Cost? = null,
) {
    val id: String = dir.fileName.toString()
    val started: OffsetDateTime = OffsetDateTime.parse(meta["started_at"] as String)

    val status: String
        get() = end?.get("status") as? String
            // Ferry is copying it from another machine: this computer may have slept, so its heartbeat says nothing about the run there.
            ?: if (remote != null) "running"
            else if ((heartbeatAge ?: Double.MAX_VALUE) < LOST_AFTER_SECONDS) "running" else "lost"

    val duration: Double?
        get() = end?.get("duration").asDouble()

    val exitCode: Long?
        get() = end?.get("exit_code") as? Long

    /** A quick crash or interrupt, such as a typo. Quick runs that finished count. */
    val trivial: Boolean
        get() = end != null && status != "finished" && (duration ?: 0.0) < TRIVIAL_SECONDS

    val why: String? get() = latestNote("why", meta["why"] as? String)
    val conclusion: String? get() = latestNote("note", null)
    val verdict: String? get() = latestNote("verdict", null)
    val parent: String? get() = latestNote("parent", meta["parent"] as? String)
    val line: String? get() = meta["line"] as? String
    val command: List<String> get() = meta["command"].asList().map { it.toString() }
    val git: Map<String, Any?>? get() = meta["git"] as? Map<String, Any?>
    val host: Map<String, Any?> get() = meta["host"].asMap()

    private val summary: MetricSummary by lazy {
        if (end != null) {
            MetricSummary(
                last = end["metrics"].asMap(),
                keys = end["metric_keys"].asList().map { it.toString() },
                sparks = end["sparks"].asMap().mapValues { (_, values) -> values.asList().mapNotNull { it.asDouble() } },
            )
        } else {
            MetricSummary.of(liveRows)
        }
    }

    val finalMetrics: Map<String, Any?> get() = summary.last
    val metricKeys: List<String> get() = summary.keys
    fun spark(key: String): List<Double> = summary.sparks[key].orEmpty()

    /** The project setting, then the run's own, then the first metric with "val", then the last one. */
    fun mainMetric(override: String?): String? {
        if (!override.isNullOrEmpty()) return override
        (meta["main_metric"] as? String)?.let { if (it.isNotEmpty()) return it }
        return metricKeys.firstOrNull { "val" in it.lowercase() } ?: metricKeys.lastOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> latestNote(kind: String, fallback: T): T {
        val event = notes.lastOrNull { it["kind"] == kind } ?: return fallback
        return event["value"] as T
    }

    companion object {
        const val TRIVIAL_SECONDS = 30.0
        const val LOST_AFTER_SECONDS = 300.0
    }
}

/** The last value of each metric, metric names in first-seen order, and short series for sparklines. */
class MetricSummary(val last: Map<String, Any?>, val keys: List<String>, val sparks: Map<String, List<Double>>) {
    companion object {
        private const val SPARK_POINTS = 32

        fun of(rows: List<Map<String, Any?>>): MetricSummary {
            val last = LinkedHashMap<String, Any?>()
            val series = LinkedHashMap<String, MutableList<Double>>()
            for (row in rows) {
                for ((key, value) in row) {
                    if (key.startsWith("_")) continue
                    last[key] = value
                    if (value is Number) series.getOrPut(key) { mutableListOf() }.add(value.toDouble())
                }
            }
            return MetricSummary(last, last.keys.toList(), series.mapValues { (_, values) -> downsample(values, SPARK_POINTS) })
        }

        /** Averages values into at most `points` buckets, skipping NaN and infinity. */
        fun downsample(values: List<Double>, points: Int): List<Double> {
            if (values.size <= points) return values
            return (0 until points).map { i ->
                val chunk = values.subList(i * values.size / points, (i + 1) * values.size / points)
                val finite = chunk.filter { it.isFinite() }
                if (finite.isEmpty()) chunk.last() else finite.average()
            }
        }
    }
}
