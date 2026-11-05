package com.github.sigmoidide.hunch.model

import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * A milestone folder, read back: an entry the user saved, covering the runs since the
 * previous one. Mirrors hunch's milestones.py; see "Milestones" in hunch's docs/format.md.
 */
class Milestone(val dir: Path, val meta: Map<String, Any?>) {
    val id: String = dir.fileName.toString()
    val created: OffsetDateTime = OffsetDateTime.parse(meta["created_at"] as String)

    val title: String get() = meta["title"] as? String ?: ""
    val description: String? get() = (meta["description"] as? String)?.takeIf { it.isNotBlank() }
    val summary: String get() = meta["summary"] as? String ?: ""
    val runs: List<String> get() = meta["runs"].asList().map { it.toString() }
    val best: String? get() = meta["best"] as? String
    val metric: String? get() = meta["metric"] as? String
    val line: String? get() = meta["line"] as? String
}
