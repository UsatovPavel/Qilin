package qilin.ucfs.benchmark

import qilin.ucfs.graph.ConstraintGraph
import qilin.ucfs.graph.EdgeKind
import qilin.ucfs.ucfs.RSM_Provider
import qilin.ucfs.ucfs.RSM_Provider.PlainRsm

/**
 * Worklist (difference-propagation) Andersen solver over a [ConstraintGraph], with no type and no
 * dispatch filtering: the same semantics as [PlainRsm], computed the classic way.
 * Used as a non-CFL baseline for the same problem (and checked against the naive oracle in tests).
 */
object ReferenceAndersenSolver {
    fun solve(g: ConstraintGraph): Map<Int, Set<Int>> {
        val n = g.vertexCount
        val succ = ArrayList<HashSet<Int>>(n)
        val pts = ArrayList<HashSet<Int>>(n)
        val delta = ArrayList<HashSet<Int>>(n)
        repeat(n) { succ.add(HashSet()); pts.add(HashSet()); delta.add(HashSet()) }
        val loadsByBase = HashMap<Int, MutableList<Pair<Int, Int>>>()   // base -> (field, target)
        val storesByBase = HashMap<Int, MutableList<Pair<Int, Int>>>()  // base -> (field, value var)
        val fieldNode = HashMap<Long, Int>()
        val work = ArrayDeque<Int>()
        val inWork = HashSet<Int>()

        fun node(o: Int, f: Int): Int = fieldNode.getOrPut(o.toLong() * (g.fieldCount + 1) + f) {
            succ.add(HashSet()); pts.add(HashSet()); delta.add(HashSet()); succ.size - 1
        }
        fun push(v: Int) { if (inWork.add(v)) work.addLast(v) }
        fun addAll(to: Int, objs: Collection<Int>) {
            for (o in objs) if (pts[to].add(o)) delta[to].add(o)
            if (delta[to].isNotEmpty()) push(to)
        }
        fun addEdge(from: Int, to: Int) { if (succ[from].add(to)) addAll(to, pts[from].toList()) }

        for (e in g.edges) when (e.kind) {
            EdgeKind.ALLOC -> addAll(e.dst, listOf(e.src))
            EdgeKind.ASSIGN -> succ[e.src].add(e.dst)
            EdgeKind.LOAD -> loadsByBase.getOrPut(e.src) { ArrayList() }.add(e.field to e.dst)
            EdgeKind.STORE -> storesByBase.getOrPut(e.dst) { ArrayList() }.add(e.field to e.src)
        }
        while (work.isNotEmpty()) {
            val v = work.removeFirst()
            inWork.remove(v)
            val d = delta[v].toList()
            delta[v].clear()
            if (d.isEmpty()) continue
            for (s in succ[v].toList()) addAll(s, d)
            if (v < n) {
                loadsByBase[v]?.forEach { (f, x) -> for (o in d) addEdge(node(o, f), x) }
                storesByBase[v]?.forEach { (f, y) -> for (o in d) addEdge(y, node(o, f)) }
            }
        }
        val res = HashMap<Int, Set<Int>>()
        for (v in 0 until n) if (!g.isAlloc[v] && pts[v].isNotEmpty()) res[v] = pts[v]
        return res
    }
}
