package qilin.ucfs

import org.ucfs.input.InputGraph
import org.ucfs.input.TerminalInputLabel
import org.ucfs.parser.Gll
import org.ucfs.rsm.RsmState
import org.ucfs.rsm.symbol.Nonterminal
import org.ucfs.rsm.symbol.Term

/**
 * Typed points-to grammar, version 2 ("shared"): the same language as [TypedPointsToGrammar] (Spark's
 * filters as gates over a [TypedGraph]) but shaped so that GLL shares sub-results the way Spark shares
 * points-to sets. UCFS keys a GSS node by (nonterminal, vertex) and replays its popped results to every
 * caller, so every value that Spark memoises becomes a nonterminal call:
 *
 *   PT      -> alloc_t                                   (any tag)
 *            | assign PT
 *            | gate_k PT tag_t                           (t ∈ P_k: checked on the object vertex after the call)
 *            | load_f PT FP_f                            (x = b.f: objects of b, then the contents of o.f)
 *   FP_f    -> FT store_f PT                             (contents of o.f: o flows to b', b'.f = y, objects of y)
 *   FT      -> alloc_r_t H_t                             (t not an empty array: only base objects use FT)
 *   H_t     -> ε | assign_r H_t | gate_r_k H_t (t ∈ P_k) | BR H_t
 *   BR      -> store_r_f PT FL_f                         (o stored into b'.f, o' ∈ pts(b'), then loads of o'.f)
 *   FL_f    -> FT load_r_f                               (variables loading from o'.f)
 *
 * PT(x) is called once per variable x (GSS node (PT, x)) and returns exactly Spark's pts(x); FP_f(o) is
 * Spark's field node o.f; FT(o) starts only at objects. Gates no longer multiply the states of PT: the tag
 * of the returned object is read from a self-loop `o -tag_t-> o` on every allocation vertex.
 */
class TypedPointsToShared(tg: TypedGraph) {
    private val terms = HashMap<String, TerminalInputLabel>()
    fun label(s: String): TerminalInputLabel = terms.getOrPut(s) { TerminalInputLabel(Term(s)) }
    private fun t(s: String) = label(s).terminal

    val pt = Nonterminal("PT")
    val ft = Nonterminal("FT")
    val br = Nonterminal("BR")
    private val fp = Array(tg.fieldCount) { Nonterminal("FP_$it") }
    private val fl = Array(tg.fieldCount) { Nonterminal("FL_$it") }
    private val fictive = Nonterminal("fictiveStart")
    val startState: RsmState
    var rsmStates = 0
        private set

    private fun st(nt: Nonterminal, start: Boolean = false, final: Boolean = false) =
        RsmState(nt, isStart = start, isFinal = final).also { rsmStates++; if (start) nt.startState = it }

    init {
        val tags = tg.tagCount
        // PT
        val p0 = st(pt, start = true)
        val pF = st(pt, final = true)
        for (tag in 0 until tags) p0.addEdge(t("alloc_$tag"), pF)
        val p1 = st(pt)
        p0.addEdge(t("assign"), p1); p1.addEdge(pt, pF)
        for ((k, p) in tg.gates.withIndex()) {
            val g1 = st(pt); val g2 = st(pt)
            p0.addEdge(t("gate_$k"), g1); g1.addEdge(pt, g2)
            var tag = p.nextSetBit(0)
            while (tag >= 0) { g2.addEdge(t("tag_$tag"), pF); tag = p.nextSetBit(tag + 1) }
        }
        for (f in 0 until tg.fieldCount) {
            val l1 = st(pt); val l2 = st(pt)
            p0.addEdge(t("load_$f"), l1); l1.addEdge(pt, l2); l2.addEdge(fp[f], pF)
            // FP_f -> FT store_f PT
            val a0 = st(fp[f], start = true); val a1 = st(fp[f]); val a2 = st(fp[f]); val aF = st(fp[f], final = true)
            a0.addEdge(ft, a1); a1.addEdge(t("store_$f"), a2); a2.addEdge(pt, aF)
            // FL_f -> FT load_r_f
            val c0 = st(fl[f], start = true); val c1 = st(fl[f]); val cF = st(fl[f], final = true)
            c0.addEdge(ft, c1); c1.addEdge(t("load_r_$f"), cF)
        }
        // FT -> alloc_r_t H_t
        val f0 = st(ft, start = true)
        for (tag in 0 until tags) {
            if (tg.tagEmptyArray[tag]) continue // Spark never stores to / loads from empty arrays
            val h = st(ft, final = true)
            f0.addEdge(t("alloc_r_$tag"), h)
            h.addEdge(t("assign_r"), h)
            h.addEdge(br, h)
            for ((k, p) in tg.gates.withIndex()) if (p.get(tag)) h.addEdge(t("gate_r_$k"), h)
        }
        // BR -> store_r_f PT FL_f
        val b0 = st(br, start = true)
        val bF = st(br, final = true)
        for (f in 0 until tg.fieldCount) {
            val b1 = st(br); val b2 = st(br)
            b0.addEdge(t("store_r_$f"), b1); b1.addEdge(pt, b2); b2.addEdge(fl[f], bF)
        }
        val s0 = st(fictive, start = true)
        val s1 = st(fictive, final = true)
        s0.addEdge(pt, s1)
        startState = s0
    }

    fun inputGraph(tg: TypedGraph, startVars: Collection<Int>): InputGraph<Int, TerminalInputLabel> {
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
            g.addEdge(e.dst, label(fwd), e.src)
            g.addEdge(e.src, label(bwd), e.dst)
        }
        for (v in 0 until tg.tagOf.size) if (tg.tagOf[v] >= 0) g.addEdge(v, label("tag_${tg.tagOf[v]}"), v)
        for (v in startVars) g.addStartVertex(v)
        return g
    }

    companion object {
        fun solve(tg: TypedGraph, startVars: Collection<Int> = tg.base.graph.varIds(), reachOnly: Boolean = false): TypedPointsTo.Result {
            val t0 = System.nanoTime()
            val gr = TypedPointsToShared(tg)
            val input = gr.inputGraph(tg, startVars)
            val t1 = System.nanoTime()
            val roots = Gll.gll(gr.startState, input, reachabilityOnly = reachOnly).parse()
            val t2 = System.nanoTime()
            val res = HashMap<Int, MutableSet<Int>>()
            for (r in roots) {
                val range = r.inputRange ?: continue
                check(tg.isAlloc(range.to)) { "PT must end at an allocation vertex: $range" }
                res.getOrPut(range.from) { HashSet() }.add(range.to)
            }
            return TypedPointsTo.Result(res, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, gr.rsmStates, roots.size)
        }
    }
}
