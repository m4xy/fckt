package uk.m4xy.fckt.types

/**
 * Tarjan's strongly connected components of the graph [edges] over [nodes]. Each component comes
 * after every component it has edges to, and keeps the order of [nodes] within it.
 */
fun <N> stronglyConnected(nodes: List<N>, edges: (N) -> List<N>): List<List<N>> {
    var index = 0
    val indices = mutableMapOf<N, Int>()
    val low = mutableMapOf<N, Int>()
    val stack = ArrayDeque<N>()
    val onStack = mutableSetOf<N>()
    val components = mutableListOf<List<N>>()

    fun visit(v: N) {
        indices[v] = index
        low[v] = index
        index++
        stack.addLast(v)
        onStack += v
        for (w in edges(v)) {
            if (w !in indices) {
                visit(w)
                low[v] = minOf(low.getValue(v), low.getValue(w))
            } else if (w in onStack) {
                low[v] = minOf(low.getValue(v), indices.getValue(w))
            }
        }
        if (low[v] == indices[v]) {
            val component = mutableListOf<N>()
            do {
                val w = stack.removeLast()
                onStack -= w
                component += w
            } while (w != v)
            components += component.sortedBy { nodes.indexOf(it) }
        }
    }
    for (node in nodes) if (node !in indices) visit(node)
    return components
}
