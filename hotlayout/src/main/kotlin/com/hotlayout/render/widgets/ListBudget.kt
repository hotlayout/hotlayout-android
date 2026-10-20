package com.hotlayout.render.widgets

import com.hotlayout.DataScope
import com.hotlayout.model.DocumentNode

/**
 * The list budget of one screen (`x-listBudget`).
 *
 * The bound of one list (50 records) multiplies with nesting, and a document the builder did not
 * make may nest deeper. A screen draws at most [PER_SCREEN] instances of list templates, nested
 * ones included, counted in document order depth first: an instance counts before the lists inside
 * it, then the next instance of the same list, then the next list. The instance that would exceed
 * the budget is not drawn, nor anything inside it, nor any instance after it on the screen.
 *
 * The count follows the TREE of the screen for its width class, not what is shown now: a list hidden
 * by `visibleIf` or `showOn`, in an inactive tab or a folded accordion costs the same, so switching
 * a tab never moves the cut of another list. The records are those a list draws (after `where` and
 * `limit`). A group header is not an instance; the lists inside it count.
 */
internal object ListBudget {
    /** `x-listBudget.perScreen`: 50 × (1 + 50). */
    const val PER_SCREEN: Int = 2550

    /** Counting what was not drawn stops here: the report then says «at least». */
    const val COUNT_CEILING = 1_000_000

    internal class Plan(
        private val allowed: Map<Pair<String, List<Int>>, Int>,
        /** Instances drawn per list node, summed over the instances of the lists around it. */
        val drawnByList: Map<String, Int>,
        val total: Int,
        /** Instances the lists would draw without the budget and do not draw (at most the counting ceiling). */
        val notDrawn: Int,
    ) {
        /** How many of its records the list [nodeId] at the instance path [path] may draw; a list the walk never reached sits in an undrawn instance. */
        fun allowed(nodeId: String, path: List<Int>): Int = allowed[nodeId to path] ?: 0
    }

    /** The parts of one instance: the header of a group when it stands above, then the template. */
    private class Instance(val scope: DataScope, val parts: List<DocumentNode>)

    private fun instancesOf(node: DocumentNode, outer: DataScope): List<Instance> {
        val plan = RepeaterPlan(node, outer)
        val template = node.children.firstOrNull()
        val isRow = node.props["direction"]?.string == "row" || node.props["carousel"] != null
        val columns = RepeaterPlan.columns(node.props, overridden = node.columnsFromOverride)
        val header = node.children.getOrNull(RecordGroups.HEADER_CHILD)?.takeIf { RepeaterPlan.groupBy(node.props) != null && columns == null && !isRow }
        return plan.instances.map { instance ->
            Instance(instance.scope, listOfNotNull(header.takeIf { instance.headerBefore }, template))
        }
    }

    /** The plan of one screen: who draws how many, in document order depth first. */
    fun plan(root: DocumentNode, scope: DataScope, budget: Int = PER_SCREEN): Plan {
        val allowed = HashMap<Pair<String, List<Int>>, Int>()
        val drawnByList = LinkedHashMap<String, Int>()
        var used = 0
        var notDrawn = 0

        fun countAll(node: DocumentNode, outer: DataScope) {
            if (notDrawn >= COUNT_CEILING) return
            if (node.type != "Repeater") {
                node.children.forEach { countAll(it, outer) }
                return
            }
            for (instance in instancesOf(node, outer)) {
                notDrawn += 1
                instance.parts.forEach { countAll(it, instance.scope) }
            }
        }

        fun walk(node: DocumentNode, outer: DataScope, path: List<Int>) {
            if (node.type != "Repeater") {
                node.children.forEach { walk(it, outer, path) }
                return
            }
            drawnByList.putIfAbsent(node.id, 0)
            val instances = instancesOf(node, outer)
            var index = 0
            while (index < instances.size && used < budget) {
                used += 1
                drawnByList[node.id] = (drawnByList[node.id] ?: 0) + 1
                val instance = instances[index]
                instance.parts.forEach { walk(it, instance.scope, path + index) }
                index += 1
            }
            allowed[node.id to path] = index
            // What did not fit: these instances and everything inside them.
            for (instance in instances.drop(index)) {
                if (notDrawn >= COUNT_CEILING) break
                notDrawn += 1
                instance.parts.forEach { countAll(it, instance.scope) }
            }
        }

        walk(root, scope, emptyList())
        return Plan(allowed, drawnByList, used, notDrawn)
    }
}
