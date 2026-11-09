package com.github.sigmoidide.hunch.run

import com.github.sigmoidide.hunch.model.Offer
import com.github.sigmoidide.hunch.model.OffersAnswer
import com.github.sigmoidide.hunch.model.Rental
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent

/**
 * Renting a GPU on Vast.ai for one run, in one place: search words that go to Vast.ai as typed
 * (`ferry gpus 4090 europe`, half a second after the typing stops), the offers, how long the
 * machine may run at most, and the most that can cost. Ferry gives the machine back once the
 * run is home.
 */
class RentDialog(
    project: Project,
    private val interpreter: String,
    private val workingDirectory: String?,
    first: OffersAnswer.Found,
) : DialogWrapper(project) {
    private val query = SearchTextField(false)
    private val understood = JBLabel()
    private val offers = CollectionListModel<Offer>()
    private val list = JBList(offers)
    private val times = ComboBox(TIME_CHOICES.toTypedArray())
    private val other = JBTextField(8)
    private val cost = JBLabel()
    private val typing = Timer(500) { find(query.text) }.apply { isRepeats = false }
    private var ticket = 0
    private var shownWords = ""

    /** After OK: the offer, the search words it was found with, and how long at most, as Ferry's --for reads it. */
    var offer: Offer? = null
        private set
    var words = ""
        private set
    var time = ""
        private set

    init {
        title = "Rent a GPU on Vast.ai"
        setOKButtonText("Rent")
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<Offer>() {
            override fun customizeCellRenderer(list: JList<out Offer>, value: Offer?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                append(value.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("   \$${"%.3f".format(Locale.ROOT, value.price)}/h   ")
                append("${value.place}, ${Rental.number(value.memory)} GB, ${"%.1f".format(Locale.ROOT, value.reliability * 100)}% reliable, " +
                    "${value.tier} host", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        list.addListSelectionListener { showCost() }
        query.textEditor.emptyText.text = OffersAnswer.SEARCH_HINT
        query.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = typing.restart()
        })
        times.selectedIndex = TIME_CHOICES.indexOfFirst { it.value == "2h" }
        times.addActionListener {
            other.isEnabled = times.item.value.isEmpty()
            showCost()
        }
        other.isEnabled = false
        other.emptyText.text = "such as 90m"
        other.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = showCost()
        })
        display(first, "")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(query).align(AlignX.FILL) }
        row { cell(understood) }
        row { scrollCell(list).align(Align.FILL) }.resizableRow()
        row("For at most:") {
            cell(times)
            cell(other)
        }
        row { cell(cost) }
        row { comment("The machine destroys itself after this time, even if the run has not ended. Ferry gives it back once the run is home.") }
    }.apply { preferredSize = JBUI.size(680, 440) }

    override fun getPreferredFocusedComponent(): JComponent = query.textEditor

    private fun seconds(): Int? = Rental.seconds(times.item.value.ifEmpty { other.text })

    private fun showCost() {
        val chosen = list.selectedValue
        val seconds = seconds()
        cost.text = when {
            chosen == null -> "Pick a GPU above."
            seconds == null -> WRONG_TIME
            else -> "${chosen.label} for \$${"%.3f".format(Locale.ROOT, chosen.price)} an hour: ${Rental.cost(chosen, seconds)}."
        }
    }

    /** Asks Vast.ai for offers matching the words, off the UI thread; a newer search wins over an older one. */
    private fun find(text: String) {
        val mine = ++ticket
        understood.text = "Searching Vast.ai..."
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val modality = ModalityState.stateForComponent(contentPane)
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val answer = Ferry.offers(interpreter, workingDirectory, words)
            app.invokeLater({ if (mine == ticket) display(answer, words.joinToString(" ")) }, modality)
        }
    }

    private fun display(answer: OffersAnswer, words: String) {
        when (answer) {
            is OffersAnswer.Found -> {
                shownWords = words
                offers.replaceAll(answer.offers)
                understood.text = if (answer.offers.isEmpty()) "No GPU to rent for ${answer.understood} right now."
                else "${answer.offers.size} offers for ${answer.understood}, cheapest first"
                if (answer.offers.isNotEmpty()) list.selectedIndex = 0
            }
            OffersAnswer.OldFerry -> understood.text = "Renting a GPU needs Ferry 0.2 or newer."
            is OffersAnswer.Failed -> understood.text = answer.message
        }
        showCost()
    }

    override fun doValidate(): ValidationInfo? = when {
        list.selectedValue == null -> ValidationInfo("Pick a GPU to rent", list)
        seconds() == null -> ValidationInfo(WRONG_TIME, if (other.isEnabled) other else times)
        else -> null
    }

    override fun doOKAction() {
        offer = list.selectedValue
        words = shownWords
        time = times.item.value.ifEmpty { other.text.trim().lowercase() }
        super.doOKAction()
    }

    override fun dispose() {
        typing.stop()
        super.dispose()
    }

    private data class TimeChoice(val value: String, val label: String) {
        override fun toString() = label
    }

    private companion object {
        const val WRONG_TIME = "A time such as 2h, 90m or 1h30m, at least 10m."
        val TIME_CHOICES = Rental.TIMES.map { TimeChoice(it.first, it.second) } + TimeChoice("", "Other...")
    }
}
