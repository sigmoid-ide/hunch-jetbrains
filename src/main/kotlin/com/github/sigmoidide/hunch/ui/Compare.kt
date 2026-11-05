package com.github.sigmoidide.hunch.ui

import com.github.sigmoidide.hunch.model.Format
import com.github.sigmoidide.hunch.model.HunchStore
import com.github.sigmoidide.hunch.model.Run
import com.github.sigmoidide.hunch.model.Snapshot
import com.github.sigmoidide.hunch.model.asMap
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Opens what changed between two runs in the platform diff viewer: their config and
 * metrics first, then every code file that differs, read from the runs' snapshots.
 */
object Compare {
    private data class Side(val title: String, val text: String?, val fileName: String)

    fun show(project: Project, store: HunchStore, before: Run, after: Run, shortIds: Map<String, String>) {
        val service = HunchService.getInstance(project)
        service.scope.launch(Dispatchers.IO) {
            val changes = Snapshot.changes(store, before, after)
            val summaryBefore = summary(before, store.root)
            val summaryAfter = summary(after, store.root)
            withContext(Dispatchers.EDT) {
                val titleBefore = "${shortIds[before.id] ?: before.id}  ${before.why.orEmpty()}".trim()
                val titleAfter = "${shortIds[after.id] ?: after.id}  ${after.why.orEmpty()}".trim()
                val requests = mutableListOf<DiffRequest>()
                if (summaryBefore != summaryAfter) {
                    requests += request(project, "Config and metrics", Side(titleBefore, summaryBefore, "run.txt"), Side(titleAfter, summaryAfter, "run.txt"))
                }
                changes?.forEach { change ->
                    requests += request(project, change.path, Side(titleBefore, change.before, change.path), Side(titleAfter, change.after, change.path))
                }
                when {
                    changes == null && requests.isEmpty() -> notify(project, "Code can't be compared: one of these runs was recorded before Hunch 0.2 saved code.")
                    requests.isEmpty() -> notify(project, "These runs have the same code, config and metrics.")
                    else -> DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT)
                }
            }
        }
    }

    private fun request(project: Project, title: String, before: Side, after: Side): DiffRequest =
        SimpleDiffRequest(title, content(project, before), content(project, after), before.title, after.title)

    private fun content(project: Project, side: Side): DiffContent {
        val factory = DiffContentFactory.getInstance()
        val text = side.text ?: return factory.createEmpty()
        val type = FileTypeManager.getInstance().getFileTypeByFileName(side.fileName.substringAfterLast('/'))
        return factory.create(project, text, if (type.isBinary) PlainTextFileType.INSTANCE else type)
    }

    /** One line per setting and final metric, so the diff viewer can line them up. */
    private fun summary(run: Run, root: Path): String = buildString {
        appendLine("# command")
        appendLine(Format.command(run, root))
        appendLine()
        appendLine("# config")
        HunchStore.readJson(run.dir.resolve("config.json")).asMap()["values"].asMap().toSortedMap().forEach { (k, v) ->
            appendLine("$k = ${Format.value(v)}")
        }
        appendLine()
        appendLine("# final metrics")
        run.finalMetrics.toSortedMap().forEach { (k, v) -> appendLine("$k = ${Format.value(v)}") }
    }

    fun notify(project: Project, text: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance().getNotificationGroup("Hunch")
            .createNotification(text, type)
            .notify(project)
    }
}
