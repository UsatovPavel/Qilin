package qilin.ucfs.ucfs

import qilin.ucfs.graph.ConstraintGraph
import qilin.ucfs.graph.EdgeKind
import qilin.ucfs.results.UcfsResult

import org.ucfs.input.InputGraph
import org.ucfs.input.TerminalInputLabel
import org.ucfs.parser.Gll
import org.ucfs.rsm.RsmState
import org.ucfs.sppf.node.RangeSppfNode

object UcfsPointsTo {
    /** Builds the UCFS input graph of [pag] (both orientations, see [PointsToGrammar]). */
    fun inputGraph(pag: ConstraintGraph, g: PointsToGrammar, startVars: Collection<Int>): InputGraph<Int, TerminalInputLabel> {
        val graph = InputGraph<Int, TerminalInputLabel>()
        for (v in 0 until pag.vertexCount) graph.addVertex(v)
        for (e in pag.edges) {
            when (e.kind) {
                EdgeKind.ALLOC -> { // o -> x
                    graph.addEdge(e.dst, g.label(g.alloc), e.src)
                    graph.addEdge(e.src, g.label(g.allocR), e.dst)
                }
                EdgeKind.ASSIGN -> { // y -> x
                    graph.addEdge(e.dst, g.label(g.assign), e.src)
                    graph.addEdge(e.src, g.label(g.assignR), e.dst)
                }
                EdgeKind.STORE -> { // y -> b.f : src = y, dst = b
                    graph.addEdge(e.dst, g.label(g.store(e.field)), e.src)
                    graph.addEdge(e.src, g.label(g.storeR(e.field)), e.dst)
                }
                EdgeKind.LOAD -> { // b.f -> x : src = b, dst = x
                    graph.addEdge(e.dst, g.label(g.load(e.field)), e.src)
                    graph.addEdge(e.src, g.label(g.loadR(e.field)), e.dst)
                }
            }
        }
        for (v in startVars) graph.addStartVertex(v)
        return graph
    }

    /** Points-to sets of [startVars] (default: every variable vertex) computed by UCFS. */
    fun solve(pag: ConstraintGraph, startVars: Collection<Int> = pag.varIds()): UcfsResult {
        val t0 = System.nanoTime()
        val g = PointsToGrammar(pag.fieldCount)
        val graph = inputGraph(pag, g, startVars)
        val t1 = System.nanoTime()
        val roots = Gll.gll(g.startState, graph).parse()
        val t2 = System.nanoTime()
        return UcfsResult(pairsOf(roots, pag), roots, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000)
    }

    fun pairsOf(roots: Set<RangeSppfNode<Int>>, pag: ConstraintGraph): Map<Int, Set<Int>> {
        val res = HashMap<Int, MutableSet<Int>>()
        for (r in roots) {
            val range = r.inputRange ?: continue
            check(pag.isAlloc[range.to]) { "PointsTo must end at an allocation vertex: $range" }
            res.getOrPut(range.from) { HashSet() }.add(range.to)
        }
        return res
    }

    @Suppress("unused")
    fun rsmStates(start: RsmState) = start.nonterminal.getStates()
}
