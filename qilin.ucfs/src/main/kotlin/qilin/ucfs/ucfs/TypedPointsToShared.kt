package qilin.ucfs.ucfs

import qilin.ucfs.graph.TypedGraph
import qilin.ucfs.model.TypedPointsToResult
import org.ucfs.input.InputGraph
import org.ucfs.input.TerminalInputLabel
import org.ucfs.parser.Gll

/** Shared-v2 typed points-to solver; its RSM is built by [RSM_Provider]. */
object TypedPointsToShared {
    private fun inputGraph(tg: TypedGraph, gr: RSM_Provider.TypedRsm, startVars: Collection<Int>): InputGraph<Int, TerminalInputLabel> {
        val g = InputGraph<Int, TerminalInputLabel>()
        for (v in 0 until tg.vertexCount) g.addVertex(v)
        for (e in tg.edges) {
            val (fwd, bwd) = when (e.kind) {
                TypedGraph.L.ASSIGN -> "assign" to "assign_r"
                TypedGraph.L.GATE -> "gate_${e.arg}" to "gate_r_${e.arg}"
                TypedGraph.L.ALLOC -> "alloc_${e.arg}" to "alloc_r_${e.arg}"
                TypedGraph.L.LOAD -> "load_${e.arg}" to "load_r_${e.arg}"
                TypedGraph.L.STORE -> "store_${e.arg}" to "store_r_${e.arg}"
            }
            // value flow src -> dst; the PT edge goes against it (dst -> src), the FT edge along it.
            // STORE y -> b.f has src = y, dst = b: PT edge b -store_f-> y, FT edge y -store_r_f-> b.
            // LOAD b.f -> x has src = b, dst = x: PT edge x -load_f-> b, FT edge b -load_r_f-> x.
            g.addEdge(e.dst, gr.label(fwd), e.src)
            g.addEdge(e.src, gr.label(bwd), e.dst)
        }
        for (v in 0 until tg.tagOf.size) if (tg.tagOf[v] >= 0) g.addEdge(v, gr.label("tag_${tg.tagOf[v]}"), v)
        for (v in startVars) g.addStartVertex(v)
        return g
    }

    fun solve(tg: TypedGraph, startVars: Collection<Int> = tg.base.graph.varIds(), reduce: Boolean = false, reachOnly: Boolean = false): TypedPointsToResult {
        if (reduce) {
            val (g2, rep) = tg.reduced()
            val result = solve(g2, startVars.map { rep[it] }.distinct(), reduce = false, reachOnly = reachOnly)
            val expanded = HashMap<Int, Set<Int>>()
            for (v in startVars) result.pointsTo[rep[v]]?.let { expanded[v] = it }
            return TypedPointsToResult(expanded, result.graphMillis, result.solveMillis, result.constraintStates, result.roots).also {
                it.reducedStarts = startVars.map { rep[it] }.distinct().size
                it.reducedEdges = g2.edges.size
            }
        }
        val t0 = System.nanoTime()
        val gr = RSM_Provider.typed(tg)
        val input = inputGraph(tg, gr, startVars)
        val t1 = System.nanoTime()
        val roots = Gll.gll(gr.startState, input, reachabilityOnly = reachOnly).parse()
        val t2 = System.nanoTime()
        val res = HashMap<Int, MutableSet<Int>>()
        for (r in roots) {
            val range = r.inputRange ?: continue
            check(tg.isAlloc(range.to)) { "PT must end at an allocation vertex: $range" }
            res.getOrPut(range.from) { HashSet() }.add(range.to)
        }
        return TypedPointsToResult(res, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, gr.rsmStates, roots.size)
    }
}
