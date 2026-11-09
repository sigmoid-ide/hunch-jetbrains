package com.github.sigmoidide.hunch.run

import com.intellij.execution.Executor
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.TextWithMnemonic
import com.intellij.openapi.wm.ToolWindowId
import javax.swing.Icon

/**
 * "Run with Hunch on...": Run with Hunch on one of the user's other machines, with Ferry. Its
 * console, which follows the run there, opens in the normal Run tool window.
 */
class FerryExecutor : Executor() {
    override fun getId(): String = ID
    override fun getToolWindowId(): String = ToolWindowId.RUN
    override fun getToolWindowIcon(): Icon = AllIcons.Toolwindows.ToolWindowRun
    override fun getIcon(): Icon = ICON
    override fun getDisabledIcon(): Icon? = null
    override fun getDescription(): String = "Run the selected configuration on one of your machines with Ferry, and record it with Hunch"
    override fun getActionName(): String = "Hunch On"
    override fun getStartActionText(): String = "Run with Hunch _on..."
    override fun getContextActionId(): String = "Hunch.RunWithHunchOnContext"
    override fun getHelpId(): String? = null

    override fun getStartActionText(configurationName: String): String {
        if (configurationName.isEmpty()) return getStartActionText()
        return TextWithMnemonic.parse("Run '%s' with Hunch _on...").replaceFirst("%s", shortenNameIfNeeded(configurationName)).toString()
    }

    companion object {
        const val ID = "RunWithHunchOn"
        private val ICON = IconLoader.getIcon("/icons/runWithHunch.svg", FerryExecutor::class.java)
    }
}
