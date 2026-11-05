package com.github.sigmoidide.hunch.model

/**
 * Lays runs out like `git log --graph --topo-order`, the same way `hunch log` does:
 * each run's descendants sit right above it, newest branch first, and lanes are
 * a stack, so branches only ever join into the lane directly to their left.
 */
object RunGraph {

    /** One table row: a run, the lane its node sits in, and the lanes around it. */
    data class Row(
        val run: Run,
        /** Lane of this run's node. */
        val lane: Int,
        /** Lanes coming in from the row above, as the run id each one is waiting for. */
        val above: List<String>,
        /** Lanes going out to the row below. */
        val below: List<String>,
        /** Indexes in [above] that end at this node (this run's children). */
        val joining: List<Int>,
        val hasParent: Boolean,
    ) {
        val width: Int get() = maxOf(above.size, below.size, lane + 1)
    }

    /** Hides quick crashes, except those that other shown runs came from. */
    fun visible(runs: List<Run>, showAll: Boolean): List<Run> {
        if (showAll) return runs
        val byId = runs.associateBy { it.id }
        val keep = runs.filter { !it.trivial }.map { it.id }.toMutableSet()
        val pending = ArrayDeque(keep)
        while (pending.isNotEmpty()) {
            val parent = byId[pending.removeLast()]?.parent ?: continue
            if (parent in byId && keep.add(parent)) pending.add(parent)
        }
        return runs.filter { it.id in keep }
    }

    fun rows(runs: List<Run>): List<Row> {
        val byId = runs.associateBy { it.id }
        val parentOf = runs.associate { run ->
            val parent = run.parent?.let(byId::get)
            run.id to parent?.takeIf { it.started < run.started }?.id
        }
        val children = runs.associate { it.id to mutableListOf<Run>() }
        val roots = mutableListOf<Run>()
        for (run in runs) {
            val parent = parentOf[run.id]
            if (parent != null) children.getValue(parent).add(run) else roots.add(run)
        }

        // Newest start time in each run's subtree. Children start after their parent,
        // so going from newest to oldest sees every child before its parent.
        val latest = HashMap<String, java.time.OffsetDateTime>()
        for (run in runs.sortedByDescending { it.started }) {
            latest[run.id] = (children.getValue(run.id).map { latest.getValue(it.id) } + run.started).max()
        }

        // Post-order without recursion, newest subtree first.
        val order = mutableListOf<Run>()
        val stack = ArrayDeque<Pair<Run, Boolean>>()
        roots.sortedBy { latest.getValue(it.id) }.forEach { stack.addLast(it to false) }
        while (stack.isNotEmpty()) {
            val (run, expanded) = stack.removeLast()
            if (expanded) {
                order.add(run)
                continue
            }
            stack.addLast(run to true)
            children.getValue(run.id).sortedBy { latest.getValue(it.id) }.forEach { stack.addLast(it to false) }
        }

        val lanes = mutableListOf<String>()
        return order.map { run ->
            val above = lanes.toList()
            val joining = above.indices.filter { above[it] == run.id }
            val lane: Int
            if (joining.isNotEmpty()) {
                lane = joining.first()
                // The other children's lanes are to the right of the first one; close them.
                for (index in joining.drop(1).reversed()) lanes.removeAt(index)
            } else {
                lanes.add(run.id)
                lane = lanes.lastIndex
            }
            val parent = parentOf[run.id]
            if (parent != null) lanes[lane] = parent else lanes.removeAt(lane)
            Row(run, lane, above, lanes.toList(), joining, parent != null)
        }
    }
}
