package com.github.sigmoidide.hunch.run

import com.github.sigmoidide.hunch.model.MachinesAnswer
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import java.nio.charset.StandardCharsets

/** The interpreter's Ferry, which runs Hunch runs on the user's other machines and brings them home. */
object Ferry {
    private const val TIMEOUT_MILLIS = 60_000
    private const val README = "https://github.com/sigmoid-ide/ferry#readme"

    /** The machines Ferry knows (`ferry add`). Blocks, so call it off the UI thread. */
    fun machines(interpreter: String, workingDirectory: String?): MachinesAnswer {
        val command = GeneralCommandLine(interpreter, "-m", "ferry", "machines", "--json")
            .withWorkDirectory(workingDirectory)
            .withEnvironment("PYTHONIOENCODING", "utf-8")
            .withCharset(StandardCharsets.UTF_8)
        val output = try {
            CapturingProcessHandler(command).runProcess(TIMEOUT_MILLIS)
        } catch (e: ExecutionException) {
            return MachinesAnswer.Failed(e.message ?: "Couldn't start $interpreter.")
        }
        if (output.isTimeout) return MachinesAnswer.Failed("Ferry did not answer in time.")
        return MachinesAnswer.of(output.exitCode == 0, output.stdout, output.stderr)
    }

    /** Says why there is no machine to choose. */
    fun notify(project: Project, interpreter: String, answer: MachinesAnswer) {
        val install = GeneralCommandLine(interpreter, "-m", "pip", "install", "-U", "sigmoid-ferry").commandLineString
        val code = "<code>${StringUtil.escapeXmlEntities(install)}</code>"
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Hunch")
        val notification = when (answer) {
            MachinesAnswer.NoFerry -> group.createNotification(
                "Ferry is not installed",
                "Run with Hunch on... runs on your other machines with Ferry, the sigmoid-ferry package, in this interpreter. Install it with:<br>$code",
                NotificationType.WARNING,
            ).addAction(NotificationAction.createSimpleExpiring("Copy command") { CopyPasteManager.copyTextToClipboard(install) })
            MachinesAnswer.OldFerry -> group.createNotification(
                "Ferry is too old",
                "Run with Hunch on... needs Ferry 0.1.2 or newer. Upgrade it with:<br>$code",
                NotificationType.WARNING,
            ).addAction(NotificationAction.createSimpleExpiring("Copy command") { CopyPasteManager.copyTextToClipboard(install) })
            is MachinesAnswer.Found -> group.createNotification(
                "Ferry has no machines yet",
                "Add one in a terminal: <code>ferry add mybox user@host</code>",
                NotificationType.INFORMATION,
            )
            is MachinesAnswer.Failed -> group.createNotification(
                "Ferry couldn't list your machines", StringUtil.escapeXmlEntities(answer.message), NotificationType.ERROR,
            )
        }
        notification.addAction(NotificationAction.createSimple("How Ferry works") { BrowserUtil.browse(README) }).notify(project)
    }
}
