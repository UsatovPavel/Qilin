package qilin.ucfs.ucfs

import qilin.ucfs.graph.TypedGraph
import qilin.ucfs.model.RsmResult
import qilin.ucfs.benchmark.RsmProfile
import org.ucfs.input.TerminalInputLabel
import org.ucfs.parser.Gll
import org.ucfs.rsm.RsmState

object RsmSolve {


    /** heap: System.gc() before building the input graph and right after parse (GLL structures still alive); liveHeapMb = difference */
    fun solve(tg0: TypedGraph, design: String, vars: Collection<Int>, reduce: Boolean, reachOnly: Boolean, profile: Boolean,
              heap: Boolean = false): RsmResult {
        val rt = Runtime.getRuntime()
        fun used(): Long { System.gc(); System.gc(); return rt.totalMemory() - rt.freeMemory() }
        val (tg, rep) = if (reduce) tg0.reduced() else tg0 to IntArray(tg0.vertexCount) { it }
        val heap0 = if (heap) used() else 0L
        val t0 = System.nanoTime()
        val d = RSM_Provider.design(design, tg)
        val repVars = vars.map { rep[it] }.distinct()
        val input = RsmInputGraph.build(tg, d, RsmInputGraph.starts(tg, d, repVars))
        val t1 = System.nanoTime()
        val mx = java.lang.management.ManagementFactory.getThreadMXBean()
        val c1 = mx.currentThreadCpuTime
        val gll = Gll.gll(d.startState, input, reachabilityOnly = reachOnly)
        var roots: Set<org.ucfs.sppf.node.RangeSppfNode<Int>> = gll.parse()
        if (d is RSM_Provider.TypedDesign.FtFirst && d.noFictive) {
            // results = ranges popped from the GSS nodes (FT.start, o) of the start objects; then VE for empty arrays
            fun popped(g: Gll<Int, TerminalInputLabel>, st: RsmState) =
                g.ctx.gss.nodes.keys.filter { it.rsm === st }.flatMap { it.popped }
            val all = HashSet(popped(gll, d.ftStart))
            val empties = (0 until tg.vertexCount).filter { tg.isAlloc(it) && tg.tagEmptyArray[tg.tagOf[it]] }
            if (empties.isNotEmpty()) {
                val in2 = RsmInputGraph.build(tg, d, empties)
                val g2 = Gll.gll(d.veStart, in2, reachabilityOnly = reachOnly)
                g2.parse()
                all.addAll(popped(g2, d.veStart))
            }
            roots = all
        }
        val t2 = System.nanoTime()
        val c2 = mx.currentThreadCpuTime
        val live = if (heap) (used() - heap0) / (1024 * 1024) else -1L
        if (heap) println("PHASE liveHeapMb=$live")
        if (profile) RsmProfile.report(gll, d)
        val nBase = tg.base.nodes.size
        // after a rewrite (M14 Cut-Shortcut) the reduction may pick a shadow vertex as the representative of a variable
        val repSet = repVars.toHashSet()
        val byRep = HashMap<Int, MutableSet<Int>>()
        for (r in roots) {
            val range = r.inputRange ?: continue
            val (v, o) = if (d.rootsFromVariables) range.from to range.to else range.to to range.from
            if ((v >= nBase && v !in repSet) || tg.isAlloc(v)) continue // shadow vertex that represents no variable, or not a variable
            check(tg.isAlloc(o)) { "root must pair a variable with an allocation vertex: $range" }
            byRep.getOrPut(v) { HashSet() }.add(o)
        }
        val res = HashMap<Int, Set<Int>>()
        for (v in vars) byRep[rep[v]]?.let { res[v] = it }
        return RsmResult(res, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, d.states, roots.size, (c2 - c1) / 1_000_000, live)
    }
}
