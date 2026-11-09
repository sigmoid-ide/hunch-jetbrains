package com.github.sigmoidide.hunch.run

import com.github.sigmoidide.hunch.model.MachinesAnswer
import com.github.sigmoidide.hunch.model.OffersAnswer
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
        val output = try {
            call(interpreter, workingDirectory, "machines", "--json")
        } catch (e: ExecutionException) {
            return MachinesAnswer.Failed(e.message ?: "Couldn't start $interpreter.")
        }
        if (output.isTimeout) return MachinesAnswer.Failed("Ferry did not answer in time.")
        return MachinesAnswer.of(output.exitCode == 0, output.stdout, output.stderr)
    }

    /** Whether Ferry keeps a Vast.ai key; null when this Ferry is too old to rent. Blocks. */
    fun loggedIn(interpreter: String, workingDirectory: String?): Boolean? {
        val output = try {
            call(interpreter, workingDirectory, "login", "vast", "--check")
        } catch (_: ExecutionException) {
            return false
        }
        if (output.exitCode != 0 && "invalid choice" in output.stderr) return null
        return output.exitCode == 0
    }

    /** The GPUs Ferry can rent on Vast.ai now for these search words, cheapest first (`ferry gpus --json`). Blocks. */
    fun offers(interpreter: String, workingDirectory: String?, words: List<String>): OffersAnswer {
        val output = try {
            call(interpreter, workingDirectory, "gpus", "--json", "--", *words.toTypedArray())
        } catch (e: ExecutionException) {
            return OffersAnswer.Failed(e.message ?: "Couldn't start $interpreter.")
        }
        if (output.isTimeout) return OffersAnswer.Failed("Ferry did not answer in time.")
        return OffersAnswer.of(output.exitCode == 0, output.stdout, output.stderr)
    }

    private fun call(interpreter: String, workingDirectory: String?, vararg args: String) =
        CapturingProcessHandler(
            GeneralCommandLine(interpreter, "-m", "ferry", *args)
                .withWorkDirectory(workingDirectory)
                .withEnvironment("PYTHONIOENCODING", "utf-8")
                .withCharset(StandardCharsets.UTF_8),
        ).runProcess(TIMEOUT_MILLIS)

    /** Says why renting cannot go ahead: an old Ferry, no Vast.ai key yet, or Vast.ai's answer. */
    fun notifyRent(project: Project, interpreter: String, loggedIn: Boolean?, answer: OffersAnswer?) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Hunch")
        val upgrade = GeneralCommandLine(interpreter, "-m", "pip", "install", "-U", "sigmoid-ferry").commandLineString
        val login = GeneralCommandLine(interpreter, "-m", "ferry", "login", "vast").commandLineString
        val (title, content, command) = when {
            loggedIn == null || answer == OffersAnswer.OldFerry ->
                Triple("Ferry is too old to rent", "Renting a GPU needs Ferry 0.2 or newer. Upgrade it with:", upgrade)
            !loggedIn -> Triple("Log in to Vast.ai first",
                "Renting a GPU needs your Vast.ai API key, once; Ferry keeps it in your system's keychain. In a terminal, run:", login)
            else -> Triple("Ferry couldn't list GPUs to rent",
                StringUtil.escapeXmlEntities((answer as? OffersAnswer.Failed)?.message ?: "Ferry did not answer."), null)
        }
        val body = if (command == null) content else "$content<br><code>${StringUtil.escapeXmlEntities(command)}</code>"
        val notification = group.createNotification(title, body, NotificationType.WARNING)
        if (command != null) notification.addAction(NotificationAction.createSimpleExpiring("Copy command") { CopyPasteManager.copyTextToClipboard(command) })
        notification.addAction(NotificationAction.createSimple("How Ferry works") { BrowserUtil.browse(README) }).notify(project)
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
