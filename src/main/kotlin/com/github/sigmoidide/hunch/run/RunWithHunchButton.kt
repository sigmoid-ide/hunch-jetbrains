package com.github.sigmoidide.hunch.run

import com.intellij.execution.Executor
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.jetbrains.python.run.PythonRunConfiguration

/**
 * Run with Hunch as a main toolbar button, right after the Run widget. The widget itself only
 * shows Run and Debug; every other executor waits in its More menu.
 */
class RunWithHunchButton : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val settings = e.project?.let { RunManager.getInstance(it).selectedConfiguration }
        val python = settings?.configuration is PythonRunConfiguration
        e.presentation.isEnabledAndVisible = python
        if (settings != null && python) {
            e.presentation.text = "Run '${Executor.shortenNameIfNeeded(settings.name)}' with Hunch"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val settings = e.project?.let { RunManager.getInstance(it).selectedConfiguration } ?: return
        val executor = ExecutorRegistry.getInstance().getExecutorById(HunchExecutor.ID) ?: return
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }
}
