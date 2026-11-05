package com.github.sigmoidide.hunch.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil

/** Whether an interpreter can run `python -m hunch`, and what to tell the user when it can't. */
object HunchInstallation {
    private const val MISSING = 3
    private const val CHECK = "import importlib.util, sys; sys.exit(0 if importlib.util.find_spec('hunch') else $MISSING)"
    private const val TIMEOUT_MILLIS = 30_000

    /**
     * True only when the interpreter answers that it has no `hunch` module. An interpreter that
     * fails to start, or answers slowly, counts as not missing: the launch then shows the real error.
     * Blocks, so call it off the UI thread.
     */
    fun isMissing(interpreter: String, workingDirectory: String?): Boolean {
        val command = GeneralCommandLine(interpreter, "-c", CHECK).withWorkDirectory(workingDirectory)
        val output = try {
            CapturingProcessHandler(command).runProcess(TIMEOUT_MILLIS)
        } catch (_: ExecutionException) {
            return false
        }
        return !output.isTimeout && output.exitCode == MISSING
    }

    fun notifyMissing(project: Project, interpreter: String) {
        val install = GeneralCommandLine(interpreter, "-m", "pip", "install", "sigmoid-hunch").commandLineString
        NotificationGroupManager.getInstance().getNotificationGroup("Hunch")
            .createNotification(
                "Hunch is not installed",
                "Run with Hunch needs the sigmoid-hunch package in this interpreter. Install it with:<br>" +
                    "<code>${StringUtil.escapeXmlEntities(install)}</code>",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("Copy command") { CopyPasteManager.copyTextToClipboard(install) })
            .notify(project)
    }
}
