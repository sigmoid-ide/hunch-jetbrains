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
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.GridBag
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/** Everything about the selected run, like `hunch show`, in the right half of the tool window. */
class DetailsPanel(private val onSelectRun: (String) -> Unit) : JPanel(BorderLayout()) {
    private val scroll = JBScrollPane()

    init {
        scroll.border = JBUI.Borders.empty()
        scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        add(scroll, BorderLayout.CENTER)
        showMessage("Select a run to see its details.")
    }

    fun showMessage(text: String) {
        val label = JBLabel(text, SwingConstants.CENTER)
        label.foreground = UIUtil.getContextHelpForeground()
        scroll.setViewportView(label)
    }

    fun show(run: Run, runs: Map<String, Run>, shortIds: Map<String, String>, store: HunchStore, mainMetric: String?) {
        val panel = WidthTrackingPanel()
        panel.border = JBUI.Borders.empty(10, 12)
        val grid = GridBag()
            .setDefaultAnchor(GridBagConstraints.NORTHWEST)
            .setDefaultInsets(JBUI.insets(3, 0, 3, 14))

        fun add(label: String, component: JComponent) {
            panel.add(dim(label), grid.nextLine().next())
            panel.add(component, grid.next().weightx(1.0).fillCellHorizontally().insets(3, 0, 3, 0))
        }

        fun add(label: String, value: String?) {
            if (!value.isNullOrBlank()) add(label, text(value))
        }

        val title = text(run.why ?: "No hypothesis")
        title.font = JBUI.Fonts.label().biggerOn(2f).asBold()
        if (run.why == null) title.foreground = UIUtil.getContextHelpForeground()
        panel.add(title, grid.nextLine().next().coverLine().weightx(1.0).fillCellHorizontally().insets(0, 0, 2, 0))
        panel.add(dim("${shortIds[run.id] ?: run.id}  ·  started ${run.started.format(DATE)}"), grid.nextLine().next().coverLine().insets(0, 0, 10, 0))

        add("Status", status(run))
        add("Conclusion", listOfNotNull(run.verdict, run.conclusion).joinToString(": "))
        run.parent?.let { parentId ->
            val parent = runs[parentId]
            add("Came from", ActionLink("${shortIds[parentId] ?: parentId}  ${parent?.why.orEmpty()}".trim()) { onSelectRun(parentId) })
        }
        add("Command", shortCommand(run, store.root))
        add("Code", code(run))
        add("Config", config(run))
        add("Environment", environment(run))
        add("GPU", gpus(run))
        val key = run.mainMetric(mainMetric)
        add("Metrics", run.metricKeys.sortedBy { if (it == key) 0 else 1 }
            .joinToString("\n") { "$it  ${Format.value(run.finalMetrics[it])}" })
        add("History", run.notes.joinToString("\n") { event ->
            val kind = when (event["kind"]) {
                "why" -> "hypothesis"
                "note" -> "conclusion"
                else -> event["kind"].toString()
            }
            "${event["at"].toString().take(16).replace('T', ' ')}  $kind: ${event["value"]}"
        })
        add("Folder", ActionLink(store.root.relativize(run.dir).toString()) { RevealFileAction.openDirectory(run.dir) })

        // Pushes the rows to the top.
        panel.add(JPanel().apply { isOpaque = false }, grid.nextLine().next().coverLine().weighty(1.0))
        scroll.setViewportView(panel)
    }

    private fun status(run: Run): String {
        val duration = Format.duration(run.duration)
        val state = when (run.status) {
            "finished" -> "finished in $duration"
            "running" -> "running"
            "lost" -> "lost: no end was recorded and the heartbeat stopped"
            "crashed" -> "crashed after $duration" + (run.exitCode?.let { ", exit $it" } ?: "")
            "killed" -> "stopped after $duration"
            else -> "${run.status} after $duration"
        }
        return state + (run.host["hostname"]?.let { " on $it" } ?: "")
    }

    /** `python train.py --lr 3e-4` instead of the interpreter's full path and absolute script paths. */
    private fun shortCommand(run: Run, root: Path): String = run.command.mapIndexed { index, part ->
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

    private fun code(run: Run): String {
        val manifest = HunchStore.readJson(run.dir.resolve("files.json")).asMap()
        val files = manifest["files"].asMap()
        val text = if (manifest.isEmpty()) "not saved (recorded before Hunch 0.2)" else "${files.size} file${if (files.size == 1) "" else "s"} saved"
        val git = run.git ?: return text
        val commit = (git["commit"] as? String)?.take(9) ?: "no commits yet"
        val dirty = if (git["dirty"] == true) ", with uncommitted changes" else ""
        return "$text\ngit $commit on ${git["branch"] ?: "detached HEAD"}$dirty"
    }

    private fun config(run: Run): String? =
        HunchStore.readJson(run.dir.resolve("config.json")).asMap()["values"].asMap()
            .entries.joinToString("\n") { (k, v) -> "$k = ${Format.value(v)}" }.ifEmpty { null }

    private fun environment(run: Run): String? {
        val env = HunchStore.readJson(run.dir.resolve("env.json")).asMap()
        if (env.isEmpty()) return null
        (env["error"] as? String)?.let { return it }
        val torch = env["torch"].asMap()
        val lines = mutableListOf("Python ${env["python"] ?: "?"}")
        (torch["version"] as? String)?.let { version -> lines += "torch $version" + ((torch["cuda"] as? String)?.let { ", CUDA $it" } ?: "") }
        val packages = env["packages"].asMap().size
        if (packages > 0) lines += "$packages packages"
        return lines.joinToString("\n")
    }

    private fun gpus(run: Run): String? {
        val gpus = HunchStore.readJson(run.dir.resolve("env.json")).asMap()["gpus"].asList().map { it.asMap() }
        if (gpus.isEmpty()) return null
        return gpus.groupingBy { it["name"].toString() }.eachCount()
            .entries.joinToString("\n") { (name, n) -> if (n > 1) "$n x $name" else name } +
            gpus.first()["driver"]?.let { "\ndriver $it" }.orEmpty()
    }

    private fun dim(text: String): JBLabel = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    /** Selectable text that wraps to the pane's width. */
    private fun text(value: String): JBTextArea = JBTextArea(value).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty()
        font = JBUI.Fonts.label()
        columns = 1
    }

    /** Follows the viewport's width, so text areas wrap instead of scrolling sideways. */
    private class WidthTrackingPanel : JPanel(GridBagLayout()), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) = visible.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("MMM d, HH:mm")
    }
}
