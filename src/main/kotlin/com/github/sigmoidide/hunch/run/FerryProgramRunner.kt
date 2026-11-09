package com.github.sigmoidide.hunch.run

import com.github.sigmoidide.hunch.model.MachinesAnswer
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
 * interpreter's Ferry for its machines, which one, and why, then starts the configuration's own
 * command line wrapped by [HunchCommandBuilder]. Ferry records the run here and copies it home.
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
                val machines = (answer as? MachinesAnswer.Found)?.machines.orEmpty()
                if (machines.isEmpty()) {
                    Ferry.notify(environment.project, start.interpreter, answer)
                    start.cancel()
                    return@invokeLater
                }
                MachinePopup.show(environment.project, machines) { machine ->
                    if (machine == null) start.cancel()
                    else start.askWhyAndLaunch("ferry") { why -> listOf("run", "--on", machine.name, "--why=$why") }
                }
            }
        }
        return start.descriptor.asPromise()
    }
}
