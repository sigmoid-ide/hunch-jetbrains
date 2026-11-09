package com.github.sigmoidide.hunch.run

import com.github.sigmoidide.hunch.model.Machine
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent

/** Where to run: one of Ferry's machines, or a GPU to rent. */
sealed interface Target {
    data class On(val machine: Machine) : Target
    object Rent : Target
}

/** Asks which of Ferry's machines to run on, or to rent a GPU. Enter or a click chooses, Escape or a click outside cancels. */
object MachinePopup {
    const val RENT = "Rent a GPU on Vast.ai..."

    /** Calls [onClose] once, on the UI thread: with the choice, or with null when cancelled. */
    fun show(project: Project, machines: List<Machine>, onClose: (Target?) -> Unit) {
        val labels = machines.map { "${it.name}: ${it.gpu ?: "no GPU"}" } + RENT
        choose(project, "Run with Hunch On", labels) { index ->
            onClose(index?.let { if (it < machines.size) Target.On(machines[it]) else Target.Rent })
        }
    }

    private fun choose(project: Project, title: String, labels: List<String>, onClose: (Int?) -> Unit) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labels)
            .setTitle(title)
            .setMovable(true)
            // Choosing closes the popup as OK, and the choice arrives after it closed.
            .setItemChosenCallback { label -> onClose(labels.indexOf(label)) }
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (!event.isOk) onClose(null)
                }
            })
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}
