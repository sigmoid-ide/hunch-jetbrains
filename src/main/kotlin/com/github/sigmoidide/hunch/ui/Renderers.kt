package com.github.sigmoidide.hunch.ui

import com.github.sigmoidide.hunch.model.Run
import com.github.sigmoidide.hunch.model.RunGraph
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
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

/** Paints a table cell by hand: the base for the graph, sparkline and verdict cells. */
abstract class PaintedCell : JComponent(), TableCellRenderer {
    protected var run: Run? = null
    private var rowIndex = 0
    private var table: JTable? = null
    private var selected = false

    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        this.table = table
        this.selected = isSelected
        this.rowIndex = row
        bind(value)
        return this
    }

    protected abstract fun bind(value: Any?)

    protected abstract fun paintCell(g: Graphics2D)

    override fun paintComponent(g: Graphics) {
        val table = table ?: return
        g.color = if (selected) table.selectionBackground else table.background
        g.fillRect(0, 0, width, height)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            paintCell(g2)
        } finally {
            g2.dispose()
        }
    }
}

/** The run tree: lanes, the node, and curves where branches join, like the VCS Log graph. */
class GraphCell : PaintedCell() {
    private var row: RunGraph.Row? = null

    override fun bind(value: Any?) {
        row = value as? RunGraph.Row
        run = row?.run
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
                g.draw(java.awt.geom.Line2D.Double(x(lane), 0.0, x(lane), height.toDouble()))
            }
        }
        if (row.hasParent) g.draw(java.awt.geom.Line2D.Double(x(row.lane), mid, x(row.lane), height.toDouble()))

        val r = JBUIScale.scale(4.5f).toDouble()
        val node = java.awt.geom.Ellipse2D.Double(x(row.lane) - r, mid - r, 2 * r, 2 * r)
        val run = row.run
        val (color, filled) = when {
            run.verdict == "drop" -> HunchColors.RED to false
            run.status == "crashed" || run.status == "killed" -> HunchColors.RED to true
            run.status == "running" -> HunchColors.GREEN to false
            run.status == "lost" -> HunchColors.LINE to false
            run.verdict == "keep" -> HunchColors.KEEP to true
            else -> HunchColors.LINE to true
        }
        g.color = color
        if (filled) {
            g.fill(node)
        } else {
            g.color = UIUtil.getTableBackground()
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

/** A small line chart of the main metric. */
class SparklineCell : PaintedCell() {
    private var values: List<Double> = emptyList()
    private var crashed = false

    override fun bind(value: Any?) {
        @Suppress("UNCHECKED_CAST")
        val pair = value as? Pair<List<Double>, Boolean>
        values = pair?.first.orEmpty().filter { it.isFinite() }
        crashed = pair?.second ?: false
    }

    override fun paintCell(g: Graphics2D) {
        if (values.size < 2) return
        val pad = JBUI.scale(6).toDouble()
        val w = width - 2 * pad
        val h = height - 2 * pad - JBUI.scale(4)
        val low = values.min()
        val high = values.max()
        val path = Path2D.Double()
        values.forEachIndexed { i, v ->
            val px = pad + i * w / (values.size - 1)
            val py = pad + JBUI.scale(2) + if (high == low) h / 2 else (high - v) / (high - low) * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        g.stroke = BasicStroke(JBUIScale.scale(1.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = if (crashed) HunchColors.RED else HunchColors.KEEP
        g.draw(path)
    }
}

/** The keep or drop verdict as a small outlined pill: color plus text, never color alone. */
class VerdictCell : PaintedCell() {
    private var verdict: String? = null

    override fun bind(value: Any?) {
        verdict = value as? String
    }

    override fun paintCell(g: Graphics2D) {
        val text = verdict ?: return
        val color = if (text == "keep") HunchColors.GREEN else HunchColors.RED
        g.font = JBUI.Fonts.smallFont()
        val metrics = g.fontMetrics
        val w = metrics.stringWidth(text) + JBUI.scale(12)
        val h = metrics.height + JBUI.scale(2)
        val x = JBUI.scale(6)
        val y = (height - h) / 2
        g.color = color
        g.drawRoundRect(x, y, w, h, h, h)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.drawString(text, x + JBUI.scale(6), y + (h - metrics.height) / 2 + metrics.ascent)
    }
}

/** Plain or grayed text, with the platform's own text renderer. */
class TextCell(private val style: (Run?, String) -> SimpleTextAttributes = { _, _ -> SimpleTextAttributes.REGULAR_ATTRIBUTES }) :
    ColoredTableCellRenderer() {
    override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
        @Suppress("UNCHECKED_CAST")
        val pair = value as? Pair<Run, String> ?: return
        append(pair.second, style(pair.first, pair.second))
        border = JBUI.Borders.empty(0, 6)
    }
}
