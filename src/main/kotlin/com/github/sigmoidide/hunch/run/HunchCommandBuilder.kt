package com.github.sigmoidide.hunch.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.target.value.constant
import com.jetbrains.python.run.PythonExecution
import com.jetbrains.python.run.PythonModuleExecution
import com.jetbrains.python.run.PythonScriptExecution
import com.jetbrains.python.run.PythonScriptTargetedCommandLineBuilder
import com.jetbrains.python.run.target.HelpersAwareTargetEnvironmentRequest

/**
 * Turns the configuration's `python script.py args` (or `python -m module args`) into
 * `python -m <module> <args> -- <interpreter> script.py args`, such as `python -m hunch run --why=...`
 * or `python -m ferry run --on gpu --why=...`, keeping its working directory, environment and charset.
 *
 * hunch resolves the first word after `--` with `shutil.which`, and Ferry builds the machine's
 * environment from it, so [interpreter] must be the interpreter's full path. Interpreter options go
 * after it, so they reach the user's script; PyCharm also passes them to the outer `python -m`, as it
 * does for every run.
 *
 * This is the only class that touches PyCharm's experimental PythonExecution API.
 */
class HunchCommandBuilder(
    private val module: String,
    private val args: List<String>,
    private val interpreter: String,
    private val interpreterOptions: List<String>,
) : PythonScriptTargetedCommandLineBuilder {

    override fun build(helpersAwareTargetRequest: HelpersAwareTargetEnvironmentRequest, pythonScript: PythonExecution): PythonExecution {
        val target = when (pythonScript) {
            is PythonScriptExecution -> listOf(pythonScript.pythonScriptPath ?: throw ExecutionException("No script to run"))
            is PythonModuleExecution -> listOf(constant("-m"), constant(pythonScript.moduleName ?: throw ExecutionException("No module to run")))
            else -> throw ExecutionException("Run with Hunch can only run a Python script or module")
        }
        return PythonModuleExecution().apply {
            moduleName = module
            addParameters(args)
            addParameters("--", interpreter)
            addParameters(interpreterOptions)
            target.forEach(::addParameter)
            parameters += pythonScript.parameters
            envs += pythonScript.envs
            workingDir = pythonScript.workingDir
            charset = pythonScript.charset
        }
    }
}
