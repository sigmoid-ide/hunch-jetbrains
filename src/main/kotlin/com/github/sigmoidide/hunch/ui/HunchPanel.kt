package com.github.sigmoidide.hunch.ui

import com.github.sigmoidide.hunch.model.Format
import com.github.sigmoidide.hunch.model.Run
import com.github.sigmoidide.hunch.model.RunGraph
import com.github.sigmoidide.hunch.model.Search
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.io.IOException
import java.time.format.DateTimeFormatter
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

/**
 * The Hunch tool window: milestones on the left (once there are any), the run tree of the
 * chosen milestone in the middle, and the selected run's or milestone's details on the right.
 */
class HunchPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(false, true) {
    private val service = HunchService.getInstance(project)
    private val model = RunsModel()
    private val table = JBTable(model)
    private val details = DetailsPanel(::select)
    private var state: HunchState = service.state.value
    private var showAll = false
    private var shortIds: Map<String, String> = emptyMap()

    /** The metric named in the metric column's header. */
    private var headerMetric: String? = null

    private val scopes = CollectionListModel<Scope>()
    private val scopeList = JBList(scopes)
    private val scopeScroll = ScrollPaneFactory.createScrollPane(scopeList, true)
    private val outer = OnePixelSplitter(false, "hunch.milestones.split", 0.18f)
    private var scope: Scope = Scope.Latest
    private var scopeCounts: Map<String, Int> = emptyMap()
    private var updatingScopes = false

    /** Above the table: words to find in a run. While there are any, the table shows matches from all runs. */
    private val searchField = SearchTextField(false)
    private val searching: Boolean get() = Search.words(searchField.text).isNotEmpty()

    /** Under the table: how many quick runs are hidden, so a run never just disappears. */
    private val quickRunsBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
    private val quickRunsNote = JBLabel()
    private val quickRunsLink = ActionLink("") {
        showAll = !showAll
        update(state)
    }

    /** Set after saving a milestone: the milestones before it, so the new one can be selected. */
    private var selectNewMilestoneAfter: Set<String>? = null

    init {
        configureTable()
        configureScopeList()
        configureSearch()
        val splitter = OnePixelSplitter(false, "hunch.details.split", 0.64f)
        quickRunsBar.add(quickRunsNote)
        quickRunsBar.add(quickRunsLink)
        quickRunsBar.border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(3, 6))
        quickRunsNote.foreground = UIUtil.getContextHelpForeground()
        splitter.firstComponent = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER)
            add(quickRunsBar, BorderLayout.SOUTH)
        }
        splitter.secondComponent = details
        outer.secondComponent = splitter
        setContent(outer)

        val keep = VerdictAction("keep", "Keep", "Worth building on", AllIcons.Actions.Checked)
        val drop = VerdictAction("drop", "Drop", "A dead end", AllIcons.Actions.Cancel)
        val conclude = EditTextAction("note", "Write Conclusion...", "What did this run show?", AllIcons.Actions.Edit)
        val toolbarActions = DefaultActionGroup(
            Refresh(), Separator.getInstance(),
            CompareAction(), Separator.getInstance(),
            keep, drop, conclude, Separator.getInstance(),
            SaveMilestone(), Separator.getInstance(),
            ShowAllToggle(),
            OpenFolder(),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("HunchToolbar", toolbarActions, false)
        toolbar.targetComponent = table
        setToolbar(toolbar.component)

        val menu = DefaultActionGroup(
            CompareAction(), Separator.getInstance(),
            keep, drop, conclude,
            EditTextAction("why", "Edit Hypothesis...", "Why you ran it", null), Separator.getInstance(),
            OpenFolder(),
        )
        PopupHandler.installPopupMenu(table, menu, "HunchPopup")

        val job = service.scope.launch(Dispatchers.EDT) {
            service.state.collect { update(it) }
        }
        Disposer.register(parent) { job.cancel() }
    }

    private fun configureTable() {
        table.setShowGrid(false)
        table.intercellSpacing = JBUI.emptySize()
        table.rowHeight = JBUI.scale(28)
        table.tableHeader.reorderingAllowed = false
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.emptyText.text = "Loading runs..."
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showDetails() }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) compareSelected()
            }
        })

        val columns = table.columnModel
        columns.getColumn(COL_GRAPH).cellRenderer = GraphCell()
        columns.getColumn(COL_RUN).cellRenderer = TextCell { _, _ -> SimpleTextAttributes.GRAYED_ATTRIBUTES }
        columns.getColumn(COL_STARTED).cellRenderer = TextCell { _, _ -> SimpleTextAttributes.GRAYED_ATTRIBUTES }
        columns.getColumn(COL_HYPOTHESIS).cellRenderer = TextCell { run, _ ->
            if (run?.why == null) SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        columns.getColumn(COL_METRIC).cellRenderer = MetricCell()
        columns.getColumn(COL_CONCLUSION).cellRenderer = ConclusionCell()
        fixedWidth(COL_RUN, 64)
        fixedWidth(COL_STARTED, 104)
        fixedWidth(COL_METRIC, 100 + MetricCell.CURVE_WIDTH)
        columns.getColumn(COL_HYPOTHESIS).preferredWidth = JBUI.scale(280)
        columns.getColumn(COL_CONCLUSION).preferredWidth = JBUI.scale(280)

        // What each column means, on hover; the metric column also picks the metric on click.
        val header = table.tableHeader
        header.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                header.toolTipText = when (header.columnAtPoint(e.point)) {
                    COL_RUN -> "The end of the run's id. Use it with hunch show and hunch diff."
                    COL_HYPOTHESIS -> "Why you ran it, written before the run (hunch run -m). Right-click to edit."
                    COL_METRIC -> "This metric at the end of each run, and how it moved during the run. Click to choose another metric."
                    COL_CONCLUSION -> "What the run showed, written after it, and whether to keep building on it or drop it (hunch note)."
                    else -> null
                }
            }
        })
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (header.columnAtPoint(e.point) == COL_METRIC) chooseMetric(RelativePoint(e))
            }
        })

        ActionManager.getInstance().getAction(IdeActions.ACTION_SHOW_DIFF_COMMON)?.shortcutSet?.let { shortcuts ->
            object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) = compareSelected()
            }.registerCustomShortcutSet(shortcuts, table)
        }
    }

    private fun configureScopeList() {
        scopeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        scopeList.cellRenderer = ScopeRenderer { scopeCounts }
        scopeList.addListSelectionListener { e ->
            if (e.valueIsAdjusting || updatingScopes) return@addListSelectionListener
            val chosen = scopeList.selectedValue ?: return@addListSelectionListener
            scope = chosen
            table.clearSelection()
            // Choosing a milestone ends a search, so the table shows that milestone.
            if (searchField.text.isNotEmpty()) searchField.text = "" else update(state)
        }
        scopeList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Clicking the milestone already chosen brings its details back.
                if (scope is Scope.Of) table.clearSelection()
            }
        })
    }

    private fun configureSearch() {
        searchField.textEditor.emptyText.text = "Search all runs"
        searchField.border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(3, 6))
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = update(state)
        })
        // Down moves from the search to the first match.
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_DOWN || model.rowCount == 0) return
                e.consume()
                table.requestFocusInWindow()
                table.selectionModel.setSelectionInterval(0, 0)
            }
        })
    }

    private fun fixedWidth(column: Int, width: Int) {
        val c = table.columnModel.getColumn(column)
        c.preferredWidth = JBUI.scale(width)
        c.minWidth = JBUI.scale(width)
        c.maxWidth = JBUI.scale(width)
    }

    private fun update(newState: HunchState) {
        var selectedIds = selectedRuns().map { it.id }.toSet()
        state = newState
        selectNewMilestoneAfter?.let { before ->
            val saved = newState.milestones.lastOrNull { it.id !in before } ?: return@let
            // Show the milestone just saved, with its details rather than a run's.
            selectNewMilestoneAfter = null
            scope = Scope.Of(saved, "")
            selectedIds = emptySet()
        }
        updateScopes(newState)
        val words = Search.words(searchField.text)
        val inScope = if (words.isEmpty()) scope.runs(newState) else newState.runs.filter { Search.matches(it, words, newState.store.root) }
        val shown = RunGraph.visible(inScope, showAll)
        val rows = RunGraph.rows(shown)
        shortIds = Format.shortIds(newState.runs.map { it.id })
        headerMetric = newState.mainMetric
            ?: shown.mapNotNull { it.mainMetric(null) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        model.set(rows)
        table.columnModel.getColumn(COL_METRIC).headerValue = headerMetric ?: "Metric"
        table.tableHeader.repaint()
        val graphWidth = GraphCell.width(rows.maxOfOrNull { it.width } ?: 1)
        table.columnModel.getColumn(COL_GRAPH).let { it.minWidth = graphWidth; it.maxWidth = graphWidth; it.preferredWidth = graphWidth }

        val hidden = inScope.size - shown.size
        val empty = table.emptyText
        empty.clear()
        when {
            !newState.loaded -> empty.text = "Loading runs..."
            newState.runs.isEmpty() -> {
                empty.text = "No runs yet"
                empty.appendLine("Record one with: hunch run -m \"your hypothesis\" -- python train.py")
                empty.appendLine("or add hunch.init() to your training script")
                empty.appendLine("How Hunch works", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { BrowserUtil.browse(README) }
            }
            words.isNotEmpty() && inScope.isEmpty() -> {
                empty.text = "No runs match \"${searchField.text.trim()}\""
                empty.appendLine("Search looks at hypotheses, conclusions, commands, config and run ids.")
            }
            inScope.isEmpty() && scope == Scope.Latest -> {
                empty.text = "No runs since M${newState.milestones.size}"
                empty.appendLine("Your next runs will appear here.")
            }
            inScope.isEmpty() -> empty.text = "This milestone covers no runs"
            else -> empty.text = "No runs to show"
        }
        val quick = inScope.count { it.trivial }
        val runs = if (quick == 1) "1 quick run" else "$quick quick runs"
        quickRunsBar.isVisible = hidden > 0 || (showAll && quick > 0)
        quickRunsNote.text = if (hidden > 0) {
            if (hidden == 1) "1 quick run hidden (stopped or crashed in under 30 seconds)"
            else "$hidden quick runs hidden (stopped or crashed in under 30 seconds)"
        } else {
            "Showing $runs (stopped or crashed in under 30 seconds)"
        }
        quickRunsLink.text = if (hidden > 0) "Show" else "Hide"

        rows.forEachIndexed { index, row ->
            if (row.run.id in selectedIds) table.selectionModel.addSelectionInterval(index, index)
        }
        // A milestone shows its own details until a run is picked.
        if (table.selectedRowCount == 0 && rows.isNotEmpty() && (scope !is Scope.Of || words.isNotEmpty())) table.selectionModel.setSelectionInterval(0, 0)
        showDetails()
    }

    /** Fills the milestone list, keeping the chosen entry; hides it while there are no milestones. */
    private fun updateScopes(newState: HunchState) {
        val all = Scope.all(newState)
        scope = all.firstOrNull { it.key == scope.key } ?: Scope.Latest
        scopeCounts = all.associate { s -> s.key to s.runs(newState).count { !it.trivial } }
        updatingScopes = true
        try {
            scopes.replaceAll(all)
            // While searching, no milestone is chosen: the search looks through all runs.
            if (searching) scopeList.clearSelection() else scopeList.setSelectedValue(scope, false)
        } finally {
            updatingScopes = false
        }
        val left = if (all.isEmpty()) null else scopeScroll
        if (outer.firstComponent !== left) outer.firstComponent = left
    }

    private fun showDetails() {
        val selected = selectedRuns()
        val chosen = scope
        if (selected.isEmpty() && chosen is Scope.Of && !searching) {
            details.show(chosen.milestone, chosen.number, state.runs.associateBy { it.id }, shortIds, state.store)
            return
        }
        when (selected.size) {
            0 -> details.showMessage(if (state.runs.isEmpty()) "Your runs will appear here." else "Select a run to see its details.")
            1 -> details.show(selected[0], state.runs.associateBy { it.id }, shortIds, state.store, headerMetric)
            2 -> details.showMessage("2 runs selected. Press Ctrl+D or double-click to compare them.")
            else -> details.showMessage("${selected.size} runs selected.")
        }
    }

    private fun selectedRuns(): List<Run> = table.selectedRows.toList().mapNotNull { model.rows.getOrNull(it)?.run }

    private fun select(runId: String) {
        // A run the search leaves out (such as a parent) ends the search, so it shows.
        if (searching && model.rows.none { it.run.id == runId }) searchField.text = ""
        val index = model.rows.indexOfFirst { it.run.id == runId }
        if (index < 0) return
        table.selectionModel.setSelectionInterval(index, index)
        table.scrollRectToVisible(table.getCellRect(index, 0, true))
    }

    /** Two selected runs: compare them, older first. One: compare it with the run it came from. */
    private fun compareSelected() {
        val selected = selectedRuns()
        val byId = state.runs.associateBy { it.id }
        val (before, after) = when (selected.size) {
            2 -> selected.sortedBy { it.started }.let { it[0] to it[1] }
            1 -> {
                val run = selected[0]
                val parent = run.parent?.let(byId::get)
                if (parent == null) {
                    Compare.notify(project, "This run didn't come from another run. Select two runs to compare them.")
                    return
                }
                parent to run
            }
            else -> return
        }
        Compare.show(project, state.store, before, after, shortIds)
    }

    private fun chooseMetric(where: RelativePoint) {
        val names = state.runs.flatMap { it.metricKeys }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        if (names.isEmpty()) return
        val automatic = "Automatic (first metric with \"val\" in its name)"
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(listOf(automatic) + names)
            .setTitle("Metric to Show")
            .setItemChosenCallback { choice ->
                val store = state.store
                service.scope.launch(Dispatchers.IO) {
                    store.setMainMetric(if (choice == automatic) null else choice)
                    service.refresh()
                }
            }
            .createPopup()
            .show(where)
    }

    private fun annotate(kind: String, value: String) {
        val runs = selectedRuns()
        val store = state.store
        service.scope.launch(Dispatchers.IO) {
            try {
                runs.forEach { store.annotate(it, kind, value) }
            } catch (e: IOException) {
                Compare.notify(project, "Couldn't save: ${e.message}")
            }
            service.refresh()
        }
    }

    /** A small popup with one text field: Enter saves, Escape cancels. */
    private fun askText(title: String, placeholder: String, initial: String, onSave: (String) -> Unit) {
        val field = JBTextField(initial, 44)
        field.emptyText.text = placeholder
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(field, field)
            .setTitle(title)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .createPopup()
        field.addActionListener {
            val text = field.text.trim()
            popup.closeOk(null)
            if (text.isNotEmpty()) onSave(text)
        }
        popup.showInCenterOf(table)
    }

    private fun metricValue(run: Run): MetricValue {
        val key = run.mainMetric(state.mainMetric)
        val value = key?.let { run.finalMetrics[it] }
        val label = if (key != null && key != headerMetric) "$key " else ""
        val text = when (run.status) {
            "finished" -> value?.let { label + Format.value(it) } ?: ""
            "crashed" -> run.exitCode?.let { "crashed, exit $it" } ?: "crashed"
            "killed" -> "stopped"
            "running" -> "running " + Format.duration(System.currentTimeMillis() / 1000.0 - run.started.toEpochSecond()) +
                (run.remote?.let { " on ${it.machine}" } ?: "")
            else -> run.status
        }
        return MetricValue(text, key?.let(run::spark).orEmpty(), run.status)
    }

    private inner class RunsModel : AbstractTableModel() {
        var rows: List<RunGraph.Row> = emptyList()
            private set

        fun set(newRows: List<RunGraph.Row>) {
            rows = newRows
            fireTableDataChanged()
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]

        override fun getValueAt(rowIndex: Int, column: Int): Any? {
            val row = rows[rowIndex]
            val run = row.run
            return when (column) {
                COL_GRAPH -> row
                COL_RUN -> run to (shortIds[run.id] ?: run.id)
                COL_STARTED -> run to run.started.format(DATE)
                COL_HYPOTHESIS -> run to (run.why ?: "no hypothesis")
                COL_METRIC -> metricValue(run)
                COL_CONCLUSION -> run
                else -> null
            }
        }
    }

    private abstract inner class RunAction(text: String, description: String, icon: Icon?) : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class Refresh : RunAction("Refresh", "Reload runs from .hunch", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = service.refresh()
    }

    private inner class CompareAction : RunAction("Compare", "Compare with the run it came from, or compare two selected runs", AllIcons.Actions.Diff) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = table.selectedRowCount in 1..2
        }

        override fun actionPerformed(e: AnActionEvent) = compareSelected()
    }

    private inner class VerdictAction(private val verdict: String, text: String, description: String, icon: Icon) :
        RunAction(text, description, icon) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = table.selectedRowCount > 0
        }

        override fun actionPerformed(e: AnActionEvent) = annotate("verdict", verdict)
    }

    private inner class EditTextAction(private val kind: String, text: String, description: String, icon: Icon?) :
        RunAction(text, description, icon) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = table.selectedRowCount == 1
        }

        override fun actionPerformed(e: AnActionEvent) {
            val run = selectedRuns().singleOrNull() ?: return
            if (kind == "why") {
                askText("Hypothesis", "Why did you run this?", run.why.orEmpty()) { annotate(kind, it) }
            } else {
                askText("Conclusion", "What did this run show?", run.conclusion.orEmpty()) { annotate(kind, it) }
            }
        }
    }

    private inner class SaveMilestone : RunAction(
        "Save Milestone...", "Sum up the runs since the last milestone, with your title and note, and save the code", HunchIcons.MILESTONE,
    ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = state.runs.isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            val dialog = SaveMilestoneDialog(project)
            if (!dialog.showAndGet()) return
            val args = buildList {
                add("milestone")
                // The = form and -- keep a note or title that starts with "-" from reading as an option.
                if (dialog.note.isNotBlank()) add("--note=${dialog.note.trim()}")
                add("--")
                add(dialog.milestoneTitle.trim())
            }
            val before = state.milestones.map { it.id }.toSet()
            val root = state.store.root
            service.scope.launch(Dispatchers.IO) {
                val result = HunchCli.run(project, root, args)
                withContext(Dispatchers.EDT) {
                    if (result.ok) selectNewMilestoneAfter = before
                    val message = result.message.lineSequence().first().replaceFirstChar { it.uppercase() }
                    Compare.notify(project, if (result.ok) message else result.message, if (result.ok) NotificationType.INFORMATION else NotificationType.ERROR)
                }
                service.refresh()
            }
        }
    }

    private inner class ShowAllToggle : DumbAwareToggleAction("Show Quick Crashes", "Show runs that stopped or crashed in under 30 seconds", AllIcons.General.Filter) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = showAll

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            showAll = state
            update(this@HunchPanel.state)
        }
    }

    private inner class OpenFolder : RunAction("Open Run Folder", "Show the selected run's files", AllIcons.Actions.MenuOpen) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = table.selectedRowCount == 1
        }

        override fun actionPerformed(e: AnActionEvent) {
            selectedRuns().singleOrNull()?.let { RevealFileAction.openDirectory(it.dir) }
        }
    }

    companion object {
        private const val README = "https://github.com/sigmoid-ide/hunch#readme"
        private val DATE = DateTimeFormatter.ofPattern("MMM d HH:mm")
        private val COLUMNS = arrayOf("", "Run", "Started", "Hypothesis", "Metric", "Conclusion")
        private const val COL_GRAPH = 0
        private const val COL_RUN = 1
        private const val COL_STARTED = 2
        private const val COL_HYPOTHESIS = 3
        private const val COL_METRIC = 4
        private const val COL_CONCLUSION = 5
    }
}
