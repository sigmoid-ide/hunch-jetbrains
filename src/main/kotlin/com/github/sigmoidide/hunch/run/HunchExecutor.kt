package com.github.sigmoidide.hunch.run

import com.intellij.execution.Executor
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.TextWithMnemonic
import com.intellij.openapi.wm.ToolWindowId
import javax.swing.Icon

/**
 * "Run with Hunch", shaped like the platform's "Run with Coverage": the console opens in the
 * normal Run tool window, so Stop, Rerun and traceback links behave as they do for Run.
 */
class HunchExecutor : Executor() {
    override fun getId(): String = ID
    override fun getToolWindowId(): String = ToolWindowId.RUN
    override fun getToolWindowIcon(): Icon = AllIcons.Toolwindows.ToolWindowRun
    override fun getIcon(): Icon = ICON
    override fun getDisabledIcon(): Icon? = null
    override fun getDescription(): String = "Run the selected configuration and record it with Hunch"
    override fun getActionName(): String = "Hunch"
    override fun getStartActionText(): String = "Run with _Hunch"
    override fun getContextActionId(): String = "Hunch.RunWithHunchContext"
    override fun getHelpId(): String? = null

    override fun getStartActionText(configurationName: String): String {
        if (configurationName.isEmpty()) return getStartActionText()
        return TextWithMnemonic.parse("Run '%s' with _Hunch").replaceFirst("%s", shortenNameIfNeeded(configurationName)).toString()
    }

    companion object {
        const val ID = "RunWithHunch"
        private val ICON = IconLoader.getIcon("/icons/runWithHunch.svg", HunchExecutor::class.java)
    }
}
