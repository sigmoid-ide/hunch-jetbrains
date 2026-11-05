package com.github.sigmoidide.hunch.run

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.TextComponentEmptyText
import java.util.function.Predicate

/** Asks why this run is happening. Enter answers (empty is fine), Escape or a click outside cancels. */
object HypothesisPopup {
    /** Calls [onClose] once, on the UI thread: with the hypothesis, or with null when cancelled. */
    fun show(project: Project, onClose: (String?) -> Unit) {
        val field = JBTextField(40)
        field.emptyText.text = "Why are you running this? (optional)"
        // By default the placeholder hides while the field has focus, which it always has here.
        field.putClientProperty(TextComponentEmptyText.STATUS_VISIBLE_FUNCTION, Predicate<JBTextField> { it.text.isEmpty() })
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(field, field)
            .setTitle("Hypothesis")
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    onClose(if (event.isOk) field.text.trim() else null)
                }
            })
            .createPopup()
        field.addActionListener { popup.closeOk(null) }
        popup.showCenteredInCurrentWindow(project)
    }
}
