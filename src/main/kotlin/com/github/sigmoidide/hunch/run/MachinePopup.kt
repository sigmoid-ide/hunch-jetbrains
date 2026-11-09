package com.github.sigmoidide.hunch.run

import com.github.sigmoidide.hunch.model.Machine
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent

/** Asks which of Ferry's machines to run on. Enter or a click chooses, Escape or a click outside cancels. */
object MachinePopup {
    /** Calls [onClose] once, on the UI thread: with the machine, or with null when cancelled. */
    fun show(project: Project, machines: List<Machine>, onClose: (Machine?) -> Unit) {
        val labels = machines.map { "${it.name}: ${it.gpu ?: "no GPU"}" }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labels)
            .setTitle("Run with Hunch On")
            .setMovable(true)
            // Choosing closes the popup as OK, and the choice arrives after it closed.
            .setItemChosenCallback { label -> onClose(machines[labels.indexOf(label)]) }
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (!event.isOk) onClose(null)
                }
            })
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}
