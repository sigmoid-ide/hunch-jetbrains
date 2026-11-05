package com.github.sigmoidide.hunch.ui

import com.github.sigmoidide.hunch.model.Run
import com.github.sigmoidide.hunch.model.RunGraph
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/** Colors from the New UI palette, with dark variants. */
object HunchColors {
    val KEEP = JBColor(0x6E56CF, 0xA594F9)
    val RED = JBColor(0xDB3B4B, 0xDB5C5C)
    val GREEN = JBColor(0x208A3C, 0x57965C)
    val LINE = JBColor(0x6C707E, 0xCED0D6)
}

/** Paints a table cell by hand: the base for the graph, metric and conclusion cells. */
abstract class PaintedCell : JComponent(), TableCellRenderer {
    protected var table: JTable? = null
    protected var selected = false

    protected val cellBackground: Color
        get() = table?.let { if (selected) it.selectionBackground else it.background } ?: background

    protected val cellForeground: Color
        get() = table?.let { if (selected) it.selectionForeground else it.foreground } ?: foreground

    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        this.table = table
        this.selected = isSelected
        font = table.font
        toolTipText = null
        bind(value)
        return this
    }

    protected abstract fun bind(value: Any?)

    protected abstract fun paintCell(g: Graphics2D)

    override fun paintComponent(g: Graphics) {
        g.color = cellBackground
        g.fillRect(0, 0, width, height)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            paintCell(g2)
        } finally {
            g2.dispose()
        }
    }

    /** Draws text at x, vertically centered, cut with "..." to fit `maxWidth`. Returns where it ended. */
    protected fun drawText(g: Graphics2D, text: String, x: Int, maxWidth: Int, color: Color): Int {
        g.font = font
        g.color = color
        val metrics = g.fontMetrics
        val shown = fit(text, metrics, maxWidth)
        g.drawString(shown, x, (height - metrics.height) / 2 + metrics.ascent)
        return x + metrics.stringWidth(shown)
    }

    private fun fit(text: String, metrics: FontMetrics, maxWidth: Int): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.take(end) + "...") > maxWidth) end--
        return if (end == 0) "" else text.take(end).trimEnd() + "..."
    }
}

/** The run tree: lanes, the node, and curves where branches join, like the VCS Log graph. */
class GraphCell : PaintedCell() {
    private var row: RunGraph.Row? = null

    override fun bind(value: Any?) {
        row = value as? RunGraph.Row
    }

    override fun paintCell(g: Graphics2D) {
        val row = row ?: return
        val mid = height / 2.0
        val laneWidth = JBUI.scale(LANE_WIDTH).toDouble()
        fun x(lane: Int) = JBUI.scale(PADDING) + lane * laneWidth + laneWidth / 2
        g.stroke = BasicStroke(JBUIScale.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = HunchColors.LINE
        for (lane in row.above.indices) {
            if (lane in row.joining) {
                val curve = Path2D.Double()
                curve.moveTo(x(lane), 0.0)
                curve.curveTo(x(lane), mid * 0.6, x(row.lane), mid * 0.4, x(row.lane), mid)
                g.draw(curve)
            } else {
                g.draw(Line2D.Double(x(lane), 0.0, x(lane), height.toDouble()))
            }
        }
        if (row.hasParent) g.draw(Line2D.Double(x(row.lane), mid, x(row.lane), height.toDouble()))

        val r = JBUIScale.scale(4.5f).toDouble()
        val node = Ellipse2D.Double(x(row.lane) - r, mid - r, 2 * r, 2 * r)
        val run = row.run
        val (color, filled) = when {
            run.verdict == "drop" -> HunchColors.RED to false
            run.status == "crashed" || run.status == "killed" -> HunchColors.RED to true
            run.status == "running" -> HunchColors.GREEN to false
            run.status == "lost" -> HunchColors.LINE to false
            run.verdict == "keep" -> HunchColors.KEEP to true
            else -> HunchColors.LINE to true
        }
        if (filled) {
            g.color = color
            g.fill(node)
        } else {
            g.color = cellBackground
            g.fill(node)
            g.color = color
            g.draw(node)
        }
    }

    companion object {
        const val LANE_WIDTH = 14
        const val PADDING = 6

        fun width(lanes: Int) = JBUI.scale(PADDING * 2 + LANE_WIDTH * maxOf(lanes, 1))
    }
}

/** What the metric column shows for one run: its text, how it moved, and its state. */
data class MetricValue(val text: String, val curve: List<Double>, val state: String)

/** The run's final metric value and a small curve of how it got there. */
class MetricCell : PaintedCell() {
    private var value: MetricValue? = null

    override fun bind(value: Any?) {
        this.value = value as? MetricValue
        toolTipText = this.value?.let { v ->
            val finite = v.curve.filter { it.isFinite() }
            if (finite.size < 2) v.text else "${v.text}   (from ${format(finite.first())} at the start)"
        }
    }

    override fun paintCell(g: Graphics2D) {
        val value = value ?: return
        val pad = JBUI.scale(6)
        val curveWidth = JBUI.scale(CURVE_WIDTH)
        val color = when (value.state) {
            "crashed", "killed" -> HunchColors.RED
            "running" -> HunchColors.GREEN
            "lost" -> HunchColors.LINE
            else -> cellForeground
        }
        drawText(g, value.text, pad, width - curveWidth - 3 * pad, if (selected && value.state == "finished") cellForeground else color)

        val values = value.curve.filter { it.isFinite() }
        if (values.size < 2) return
        val left = (width - curveWidth - pad).toDouble()
        val top = JBUI.scale(7).toDouble()
        val h = height - 2 * top
        val low = values.min()
        val high = values.max()
        val path = Path2D.Double()
        values.forEachIndexed { i, v ->
            val px = left + i * curveWidth.toDouble() / (values.size - 1)
            val py = top + if (high == low) h / 2 else (high - v) / (high - low) * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        g.stroke = BasicStroke(JBUIScale.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = if (value.state == "crashed" || value.state == "killed") HunchColors.RED else HunchColors.KEEP
        g.draw(path)
    }

    private fun format(v: Double) = com.github.sigmoidide.hunch.model.Format.value(v)

    companion object {
        const val CURVE_WIDTH = 64
    }
}

/** The conclusion: a keep or drop badge (color plus text, never color alone) and what the run showed. */
class ConclusionCell : PaintedCell() {
    private var run: Run? = null

    override fun bind(value: Any?) {
        run = value as? Run
        toolTipText = run?.conclusion
    }

    override fun paintCell(g: Graphics2D) {
        val run = run ?: return
        var x = JBUI.scale(6)
        run.verdict?.let { verdict ->
            val color = if (verdict == "keep") HunchColors.GREEN else HunchColors.RED
            g.font = JBUI.Fonts.smallFont()
            val metrics = g.fontMetrics
            val w = metrics.stringWidth(verdict) + JBUI.scale(12)
            val h = metrics.height + JBUI.scale(2)
            val y = (height - h) / 2
            g.color = color
            g.stroke = BasicStroke(JBUIScale.scale(1f))
            g.drawRoundRect(x, y, w, h, h, h)
            g.drawString(verdict, x + JBUI.scale(6), y + (h - metrics.height) / 2 + metrics.ascent)
            x += w + JBUI.scale(8)
        }
        run.conclusion?.let { drawText(g, it, x, width - x - JBUI.scale(6), cellForeground) }
    }
}

/** Plain or grayed text, with the platform's text renderer; the full text shows on hover. */
class TextCell(private val style: (Run?, String) -> SimpleTextAttributes = { _, _ -> SimpleTextAttributes.REGULAR_ATTRIBUTES }) :
    ColoredTableCellRenderer() {
    override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
        @Suppress("UNCHECKED_CAST")
        val pair = value as? Pair<Run, String> ?: return
        append(pair.second, style(pair.first, pair.second))
        toolTipText = pair.second
        border = JBUI.Borders.empty(0, 6)
    }
}
