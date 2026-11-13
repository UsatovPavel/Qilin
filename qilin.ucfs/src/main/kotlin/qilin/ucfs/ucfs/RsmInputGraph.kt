package qilin.ucfs.ucfs

import qilin.ucfs.graph.TypedGraph
import org.ucfs.input.InputGraph
import org.ucfs.input.TerminalInputLabel

/** Adapts the typed graph to the alphabet and site encoding expected by an RSM design. */
object RsmInputGraph {
    fun starts(tg: TypedGraph, design: RSM_Provider.TypedDesign, vars: Collection<Int>): Collection<Int> =
        if (design.rootsFromVariables) vars
        else if (design is RSM_Provider.TypedDesign.FtFirst && design.noFictive)
            (0 until tg.vertexCount).filter { tg.isAlloc(it) && !tg.tagEmptyArray[tg.tagOf[it]] }
        else (0 until tg.vertexCount).filter { tg.isAlloc(it) }

    fun build(tg: TypedGraph, design: RSM_Provider.TypedDesign, starts: Collection<Int>): InputGraph<Int, TerminalInputLabel> {
        val siteIds = HashMap<Long, Int>()
        fun site(kind: Int, b: Int, f: Int): Int =
            siteIds.getOrPut((kind.toLong() shl 62) or (b.toLong() shl 31) or f.toLong()) { tg.vertexCount + siteIds.size }
        val g = InputGraph<Int, TerminalInputLabel>()
        for (v in 0 until tg.vertexCount) g.addVertex(v)
        val siteEdge = HashSet<Long>()
        for (e in tg.edges) {
            val (fwd, bwd) = when (e.kind) {
                TypedGraph.L.ASSIGN -> "assign" to "assign_r"
                TypedGraph.L.GATE -> "gate_${e.arg}" to "gate_r_${e.arg}"
                TypedGraph.L.ALLOC -> "alloc_${e.arg}" to "alloc_r_${e.arg}"
                TypedGraph.L.LOAD -> "load_${e.arg}" to "load_r_${e.arg}"
                TypedGraph.L.STORE -> "store_${e.arg}" to "store_r_${e.arg}"
            }
            if (design.storeSites && e.kind == TypedGraph.L.STORE) {
                // y -> b.f: b = dst, y = src; PT-side store_f edge replaced by b -stp-> s(b,f) -sval_f-> y
                val s = site(0, e.dst, e.arg); g.addVertex(s)
                if (siteEdge.add(s.toLong())) g.addEdge(e.dst, design.label("stp"), s)
                g.addEdge(s, design.label("sval_${e.arg}"), e.src)
                g.addEdge(e.src, design.label(bwd), e.dst)
                continue
            }
            if (design.loadSites && e.kind == TypedGraph.L.LOAD) {
                // b.f -> x: b = src, x = dst; FT-side load_r_f edge replaced by b -ldp-> l(b,f) -lval_f-> x
                val l = site(1, e.src, e.arg); g.addVertex(l)
                if (siteEdge.add(l.toLong())) g.addEdge(e.src, design.label("ldp"), l)
                g.addEdge(l, design.label("lval_${e.arg}"), e.dst)
                g.addEdge(e.dst, design.label(fwd), e.src)
                continue
            }
            g.addEdge(e.dst, design.label(fwd), e.src) // against value flow (PT side)
            g.addEdge(e.src, design.label(bwd), e.dst) // along value flow (FT side)
        }
        if (design.tagLoops) for (v in 0 until tg.tagOf.size) if (tg.tagOf[v] >= 0) g.addEdge(v, design.label("tag_${tg.tagOf[v]}"), v)
        for (v in starts) g.addStartVertex(v)
        return g
    }
}
