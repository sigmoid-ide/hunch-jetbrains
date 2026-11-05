package com.github.sigmoidide.hunch.ui

import com.github.sigmoidide.hunch.model.Format
import com.github.sigmoidide.hunch.model.HunchStore
import com.github.sigmoidide.hunch.model.Run
import com.github.sigmoidide.hunch.model.asList
import com.github.sigmoidide.hunch.model.asMap
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Everything about the selected run, like `hunch show`, in the right half of the tool window. */
class DetailsPanel(private val onSelectRun: (String) -> Unit) : JPanel(BorderLayout()) {
    private val scroll = JBScrollPane()

    init {
        scroll.border = JBUI.Borders.empty()
        add(scroll, BorderLayout.CENTER)
        showMessage("Select a run to see its details.")
    }

    fun showMessage(text: String) {
        val label = JBLabel(text, SwingConstants.CENTER)
        label.foreground = UIUtil.getContextHelpForeground()
        scroll.setViewportView(label)
    }

    fun show(run: Run, runs: Map<String, Run>, shortIds: Map<String, String>, store: HunchStore, mainMetric: String?) {
        val form = FormBuilder.createFormBuilder()
        fun row(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            form.addLabeledComponent(dim(label), text(value), JBUI.scale(2), false)
        }

        val title = JBLabel(run.why ?: "(no why)")
        title.font = JBUI.Fonts.label().biggerOn(2f).asBold()
        form.addComponent(title, 0)
        form.addComponent(dim("${shortIds[run.id] ?: run.id}  ·  ${run.started.format(DATE)}"), JBUI.scale(2))
        form.addVerticalGap(JBUI.scale(8))

        row("Status", status(run))
        row("Verdict", listOfNotNull(run.verdict, run.conclusion?.let { "\"$it\"" }).joinToString("  "))
        run.parent?.let { parentId ->
            val parent = runs[parentId]
            val link = ActionLink("${shortIds[parentId] ?: parentId}  ${parent?.why.orEmpty()}".trim()) { onSelectRun(parentId) }
            form.addLabeledComponent(dim("Came from"), link, JBUI.scale(2), false)
        }
        row("Command", run.command.joinToString(" "))
        row("Code", code(run))
        row("Config", run.meta.let { configText(run) })
        row("Environment", environment(run))
        row("GPU", gpus(run))
        val key = run.mainMetric(mainMetric)
        val metrics = run.metricKeys.sortedBy { if (it == key) 0 else 1 }
            .joinToString("<br>") { escape("$it  ${Format.value(run.finalMetrics[it])}") }
        if (metrics.isNotEmpty()) form.addLabeledComponent(dim("Metrics"), text(metrics, html = true), JBUI.scale(2), false)
        val history = run.notes.joinToString("<br>") { event ->
            escape("${event["at"].toString().take(16).replace('T', ' ')}  ${event["kind"]}: ${event["value"]}")
        }
        if (history.isNotEmpty()) form.addLabeledComponent(dim("History"), text(history, html = true), JBUI.scale(2), false)
        val folder = ActionLink(store.root.relativize(run.dir).toString()) { RevealFileAction.openDirectory(run.dir) }
        form.addLabeledComponent(dim("Folder"), folder, JBUI.scale(2), false)

        val panel = form.panel
        panel.border = JBUI.Borders.empty(10, 12)
        val wrapper = JPanel(BorderLayout())
        wrapper.add(panel, BorderLayout.NORTH)
        scroll.setViewportView(wrapper)
    }

    private fun status(run: Run): String {
        val duration = Format.duration(run.duration)
        return when (run.status) {
            "finished" -> "finished in $duration"
            "running" -> "running"
            "lost" -> "lost: no end was recorded and the heartbeat stopped"
            "crashed" -> "crashed after $duration" + (run.exitCode?.let { ", exit $it" } ?: "")
            else -> "${run.status} after $duration"
        } + run.host["hostname"]?.let { "  on $it" }.orEmpty()
    }

    private fun code(run: Run): String {
        val manifest = HunchStore.readJson(run.dir.resolve("files.json")).asMap()
        val files = manifest["files"].asMap()
        val text = if (manifest.isEmpty()) "not saved (recorded before Hunch 0.2)" else "${files.size} file${if (files.size == 1) "" else "s"} saved"
        val git = run.git ?: return text
        val commit = (git["commit"] as? String)?.take(9) ?: "no commits yet"
        val dirty = if (git["dirty"] == true) ", uncommitted changes" else ""
        return "$text  ·  git $commit on ${git["branch"] ?: "detached HEAD"}$dirty"
    }

    private fun configText(run: Run): String? {
        val values = HunchStore.readJson(run.dir.resolve("config.json")).asMap()["values"].asMap()
        return values.entries.joinToString(", ") { (k, v) -> "$k=${Format.value(v)}" }.ifEmpty { null }
    }

    private fun environment(run: Run): String? {
        val env = HunchStore.readJson(run.dir.resolve("env.json")).asMap()
        if (env.isEmpty()) return null
        (env["error"] as? String)?.let { return it }
        val torch = env["torch"].asMap()
        val parts = mutableListOf("Python ${env["python"] ?: "?"}")
        (torch["version"] as? String)?.let { version -> parts += "torch $version" + ((torch["cuda"] as? String)?.let { ", CUDA $it" } ?: "") }
        val packages = env["packages"].asMap().size
        if (packages > 0) parts += "$packages packages"
        return parts.joinToString("  ·  ")
    }

    private fun gpus(run: Run): String? {
        val gpus = HunchStore.readJson(run.dir.resolve("env.json")).asMap()["gpus"].asList().map { it.asMap() }
        if (gpus.isEmpty()) return null
        return gpus.groupingBy { it["name"].toString() }.eachCount()
            .entries.joinToString(", ") { (name, n) -> if (n > 1) "$n x $name" else name } +
            gpus.first()["driver"]?.let { "  ·  driver $it" }.orEmpty()
    }

    private fun dim(text: String): JBLabel = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    /** `html` values are already escaped and joined with <br>. */
    private fun text(value: String, html: Boolean = false): JComponent =
        JBLabel("<html>${if (html) value else escape(value)}</html>").apply { setCopyable(true) }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("MMM d, HH:mm")
    }
}
