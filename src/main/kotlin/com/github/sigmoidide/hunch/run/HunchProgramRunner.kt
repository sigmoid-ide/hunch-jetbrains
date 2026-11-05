package com.github.sigmoidide.hunch.run

import com.intellij.concurrency.currentThreadContext
import com.intellij.concurrency.installThreadContext
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.showRunContent
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.getEelDescriptor
import com.jetbrains.python.run.PythonCommandLineState
import com.jetbrains.python.run.PythonRunConfiguration
import com.jetbrains.python.sdk.PythonSdkUtil
import kotlinx.coroutines.Job
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.asPromise
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext

/**
 * Runs a Python run configuration under `hunch run`: checks that the interpreter has hunch,
 * asks for the hypothesis, then starts the configuration's own command line wrapped by
 * [HunchCommandBuilder]. The process, console and Stop button are the ones a normal Run gets.
 */
class HunchProgramRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "HunchRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == HunchExecutor.ID && profile is PythonRunConfiguration

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val config = environment.runProfile as PythonRunConfiguration
        val interpreter = localInterpreter(config)
        // Macros such as $FilePath$ expand from the data context the platform keeps in the
        // thread context while it starts a run. The popup comes in between, so carry it over.
        val context = currentThreadContext().minusKey(Job)
        val descriptor = CompletableFuture<RunContentDescriptor?>()
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            if (HunchInstallation.isMissing(interpreter, config.workingDirectorySafe)) {
                HunchInstallation.notifyMissing(environment.project, interpreter)
                descriptor.complete(null)
                return@executeOnPooledThread
            }
            app.invokeLater {
                HypothesisPopup.show(environment.project) { hypothesis ->
                    if (hypothesis == null) {
                        descriptor.complete(null)
                    } else {
                        // hunch snapshots the code from disk when the run starts.
                        WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveAllDocuments() }
                        app.executeOnPooledThread { launch(environment, state, interpreter, hypothesis, context, descriptor) }
                    }
                }
            }
        }
        return descriptor.asPromise()
    }

    private fun launch(
        environment: ExecutionEnvironment,
        state: RunProfileState,
        interpreter: String,
        hypothesis: String,
        context: CoroutineContext,
        descriptor: CompletableFuture<RunContentDescriptor?>,
    ) {
        try {
            val pythonState = state as PythonCommandLineState
            val builder = HunchCommandBuilder(interpreter, hypothesis, pythonState.configuredInterpreterParameters)
            val result = installThreadContext(context, true) { pythonState.execute(environment.executor, builder) }
            ApplicationManager.getApplication().invokeLater { descriptor.complete(showRunContent(result, environment)) }
        } catch (e: Throwable) {
            descriptor.completeExceptionally(e)
        }
    }

    /**
     * The interpreter's path, when the run will happen on this machine. hunch must find that path
     * with `shutil.which`, which a path from SSH, Docker or WSL would defeat, so those are refused.
     */
    private fun localInterpreter(config: PythonRunConfiguration): String {
        val sdk = config.sdk ?: throw ExecutionException("'${config.name}' has no Python interpreter.")
        val home = sdk.homePath ?: throw ExecutionException("The interpreter of '${config.name}' has no path.")
        val local = !PythonSdkUtil.isRemote(sdk) &&
            try { Path.of(home).getEelDescriptor() is LocalEelDescriptor } catch (_: InvalidPathException) { false }
        if (!local) {
            throw ExecutionException(
                "Run with Hunch works only with local interpreters, and '${config.name}' uses ${sdk.name} " +
                    "on another machine (SSH, WSL or Docker). Run it there with: python -m hunch run -- python ...",
            )
        }
        return home
    }
}
