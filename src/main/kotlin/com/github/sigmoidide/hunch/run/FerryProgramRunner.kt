package com.github.sigmoidide.hunch.run

import com.github.sigmoidide.hunch.model.MachinesAnswer
import com.github.sigmoidide.hunch.model.OffersAnswer
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.jetbrains.python.run.PythonRunConfiguration
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.asPromise

/**
 * Runs a Python run configuration on one of the user's machines under `ferry run`: asks the
 * interpreter's Ferry for its machines, which one (or a GPU to rent), and why, then starts the
 * configuration's own command line wrapped by [HunchCommandBuilder]. Ferry records the run here
 * and copies it home.
 */
class FerryProgramRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "HunchFerryRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == FerryExecutor.ID && profile is PythonRunConfiguration

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val config = environment.runProfile as PythonRunConfiguration
        val start = Start(environment, state, localInterpreter(config))
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val answer = Ferry.machines(start.interpreter, config.workingDirectorySafe)
            app.invokeLater {
                if (answer !is MachinesAnswer.Found) {
                    Ferry.notify(environment.project, start.interpreter, answer)
                    start.cancel()
                    return@invokeLater
                }
                MachinePopup.show(environment.project, answer.machines) { target ->
                    when (target) {
                        null -> start.cancel()
                        is Target.On -> start.askWhyAndLaunch("ferry") { why -> listOf("run", "--on", target.machine.name, "--why=$why") }
                        Target.Rent -> rent(environment, start, config.workingDirectorySafe)
                    }
                }
            }
        }
        return start.descriptor.asPromise()
    }

    /**
     * Renting for this run: [RentDialog] (the GPUs on offer, how long at most, and the most that
     * can cost), then why. `ferry run --rent` rents the offer, and gives it back once the run is home.
     */
    private fun rent(environment: ExecutionEnvironment, start: Start, workingDirectory: String?) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val loggedIn = Ferry.loggedIn(start.interpreter, workingDirectory)
            val answer = if (loggedIn == true) Ferry.offers(start.interpreter, workingDirectory, emptyList()) else null
            app.invokeLater {
                if (answer !is OffersAnswer.Found) {
                    Ferry.notifyRent(environment.project, start.interpreter, loggedIn, answer)
                    start.cancel()
                    return@invokeLater
                }
                val dialog = RentDialog(environment.project, start.interpreter, workingDirectory, answer)
                val offer = if (dialog.showAndGet()) dialog.offer else null
                if (offer == null) {
                    start.cancel()
                    return@invokeLater
                }
                // The dialog showed the price and the most it can cost, so Ferry does not ask again. The search
                // words go along, so an offer taken instead of this one (if it fails) is found the same way.
                val choice = "${offer.id} ${dialog.words}".trim()
                start.askWhyAndLaunch("ferry") { why -> listOf("run", "--rent", choice, "--for", dialog.time, "--yes", "--why=$why") }
            }
        }
    }
}
