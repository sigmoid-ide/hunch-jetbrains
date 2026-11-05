package com.github.sigmoidide.hunch.ui

import com.github.sigmoidide.hunch.model.Milestone
import com.github.sigmoidide.hunch.model.Run
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JList

object HunchIcons {
    val MILESTONE = IconLoader.getIcon("/icons/milestone.svg", HunchIcons::class.java)
    val RUNS = IconLoader.getIcon("/icons/hunch.svg", HunchIcons::class.java)
}

/** What the run table shows: the runs since the last milestone, one milestone's runs, or all runs. */
sealed class Scope(val key: String) {
    data object Latest : Scope("latest")
    data object All : Scope("all")
    class Of(val milestone: Milestone, val number: String) : Scope(milestone.id)

    fun runs(state: HunchState): List<Run> = when (this) {
        All -> state.runs
        Latest -> {
            val covered = state.milestones.flatMapTo(HashSet()) { it.runs }
            state.runs.filter { it.id !in covered }
        }
        is Of -> {
            val members = milestone.runs.toSet()
            state.runs.filter { it.id in members }
        }
    }

    companion object {
        /** The milestone list, newest first, or nothing when there are no milestones yet. */
        fun all(state: HunchState): List<Scope> {
            if (state.milestones.isEmpty()) return emptyList()
            val numbered = state.milestones.mapIndexed { index, m -> Of(m, "M${index + 1}") }
            return listOf(Latest) + numbered.reversed() + All
        }
    }
}

class ScopeRenderer(private val counts: () -> Map<String, Int>) : ColoredListCellRenderer<Scope>() {
    override fun customizeCellRenderer(list: JList<out Scope>, value: Scope?, index: Int, selected: Boolean, hasFocus: Boolean) {
        value ?: return
        toolTipText = null
        when (value) {
            Scope.Latest -> {
                icon = AllIcons.Vcs.Changelist
                append("Since last milestone")
                toolTipText = "Runs that are not in a milestone yet"
            }
            Scope.All -> {
                icon = HunchIcons.RUNS
                append("All runs")
            }
            is Scope.Of -> {
                icon = HunchIcons.MILESTONE
                append(value.number, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  " + value.milestone.title)
                toolTipText = value.milestone.title
            }
        }
        counts()[value.key]?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
    }
}

class SaveMilestoneDialog(project: Project) : DialogWrapper(project) {
    var milestoneTitle = ""
    var note = ""

    init {
        title = "Save Milestone"
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            comment("Hunch sums up the runs on this branch since the last milestone and saves your code as it is now.")
        }
        row("Title:") {
            textField()
                .bindText(::milestoneTitle)
                .columns(44)
                .align(AlignX.FILL)
                .focused()
                .applyToComponent { emptyText.text = "e.g. warmup + cosine beats baseline" }
                .errorOnApply("Give the milestone a title") { it.text.isBlank() }
        }
        row("Note:") {
            textField()
                .bindText(::note)
                .columns(44)
                .align(AlignX.FILL)
                .applyToComponent { emptyText.text = "Optional: what you found, in your own words" }
        }
    }
}

/** Runs the hunch command line: with the project's Python interpreter when it has Hunch, else from PATH. */
object HunchCli {
    class Result(val ok: Boolean, val message: String)

    private const val TIMEOUT_MILLIS = 120_000

    suspend fun run(project: Project, workDir: Path, args: List<String>): Result {
        val commands = interpreters(project).map { listOf(it, "-m", "hunch") } + listOf(listOf("hunch"))
        var tooOld = false
        for (command in commands) {
            val output = try {
                val line = GeneralCommandLine(command + args)
                    .withWorkingDirectory(workDir)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                    .withEnvironment("PYTHONIOENCODING", "utf-8")
                    .withCharset(StandardCharsets.UTF_8)
                CapturingProcessHandler(line).runProcess(TIMEOUT_MILLIS)
            } catch (_: ExecutionException) {
                continue // no such program
            }
            if (output.exitCode != 0 && "No module named hunch" in output.stderr) continue
            if (output.exitCode != 0 && "invalid choice" in output.stderr) {
                tooOld = true // an older Hunch; another one may be newer
                continue
            }
            if (output.isTimeout) return Result(false, "Hunch didn't finish in ${TIMEOUT_MILLIS / 1000} seconds")
            val text = (if (output.exitCode == 0) output.stdout else output.stderr).trim().removePrefix("hunch: ")
            return Result(output.exitCode == 0, text.ifEmpty { "Hunch exited with code ${output.exitCode}" })
        }
        return Result(
            false,
            if (tooOld) "This needs a newer Hunch: pip install -U sigmoid-hunch"
            else "Couldn't find Hunch. Install it in the project's interpreter: pip install sigmoid-hunch",
        )
    }

    /** Local interpreters of the project's Python SDKs. */
    private suspend fun interpreters(project: Project): List<String> {
        val homes = readAction {
            val sdks = ModuleManager.getInstance(project).modules.mapNotNull { ModuleRootManager.getInstance(it).sdk } +
                listOfNotNull(ProjectRootManager.getInstance(project).projectSdk)
            sdks.mapNotNull { it.homePath }
        }
        return homes.distinct().filter { home ->
            try {
                Files.isRegularFile(Paths.get(home))
            } catch (_: InvalidPathException) {
                false // a remote interpreter, such as WSL or Docker
            }
        }
    }
}
