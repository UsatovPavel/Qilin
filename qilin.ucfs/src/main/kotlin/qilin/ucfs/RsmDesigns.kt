package qilin.ucfs

import org.ucfs.input.InputGraph
import org.ucfs.input.TerminalInputLabel
import org.ucfs.parser.Gll
import org.ucfs.rsm.RsmState
import org.ucfs.rsm.symbol.Nonterminal
import org.ucfs.rsm.symbol.Term
import java.util.IdentityHashMap

/**
 * Hand-built RSM designs for the typed points-to language over a [TypedGraph] (M13: "build the RSM directly and design
 * the optimal one for the concrete analysis"). Every design accepts the same language as [TypedPointsToShared] (v2);
 * they differ in box structure, in which nonterminal is the start symbol and in the start vertices, which changes the
 * GLL work (descriptors, GSS nodes, popped results) but not the answer. Every state carries a role label for
 * [RsmProfile].
 */
abstract class RsmDesign(val tg: TypedGraph) {
    private val terms = HashMap<String, TerminalInputLabel>()
    fun label(s: String): TerminalInputLabel = terms.getOrPut(s) { TerminalInputLabel(Term(s)) }
    protected fun t(s: String) = label(s).terminal
    val role = IdentityHashMap<RsmState, String>()
    var states = 0
        private set

    protected fun st(nt: Nonterminal, r: String, start: Boolean = false, final: Boolean = false) =
        RsmState(nt, isStart = start, isFinal = final).also { states++; role[it] = r; if (start) nt.startState = it }

    abstract val startState: RsmState

    /** roots are (variable, object) if true, (object, variable) otherwise */
    abstract val rootsFromVariables: Boolean

    /** self-loops `o -tag_t-> o` on allocation vertices (needed when a box checks the tag after a PT call) */
    open val tagLoops: Boolean = true

    /** start vertices for the requested variables */
    open fun starts(vars: Collection<Int>): Collection<Int> =
        if (rootsFromVariables) vars
        else if (this is FtFirst && noFictive) (0 until tg.vertexCount).filter { tg.isAlloc(it) && !tg.tagEmptyArray[tg.tagOf[it]] }
        else (0 until tg.vertexCount).filter { tg.isAlloc(it) }

    fun inputGraph(starts: Collection<Int>): InputGraph<Int, TerminalInputLabel> {
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
            if (storeSites && e.kind == TypedGraph.L.STORE) {
                // y -> b.f: b = dst, y = src; PT-side store_f edge replaced by b -stp-> s(b,f) -sval_f-> y
                val s = site(0, e.dst, e.arg); g.addVertex(s)
                if (siteEdge.add(s.toLong())) g.addEdge(e.dst, label("stp"), s)
                g.addEdge(s, label("sval_${e.arg}"), e.src)
                g.addEdge(e.src, label(bwd), e.dst)
                continue
            }
            if (loadSites && e.kind == TypedGraph.L.LOAD) {
                // b.f -> x: b = src, x = dst; FT-side load_r_f edge replaced by b -ldp-> l(b,f) -lval_f-> x
                val l = site(1, e.src, e.arg); g.addVertex(l)
                if (siteEdge.add(l.toLong())) g.addEdge(e.src, label("ldp"), l)
                g.addEdge(l, label("lval_${e.arg}"), e.dst)
                g.addEdge(e.dst, label(fwd), e.src)
                continue
            }
            g.addEdge(e.dst, label(fwd), e.src) // against value flow (PT side)
            g.addEdge(e.src, label(bwd), e.dst) // along value flow (FT side)
        }
        if (tagLoops) for (v in 0 until tg.tagOf.size) if (tg.tagOf[v] >= 0) g.addEdge(v, label("tag_${tg.tagOf[v]}"), v)
        for (v in starts) g.addStartVertex(v)
        return g
    }

    // ---- shared building blocks (the boxes of v2, see TypedPointsToShared) ----
    protected val pt = Nonterminal("PT")
    protected val ft = Nonterminal("FT")
    protected val br = Nonterminal("BR")
    protected val fp = Array(tg.fieldCount) { Nonterminal("FP_$it") }
    protected val fl = Array(tg.fieldCount) { Nonterminal("FL_$it") }

    /** state that walks copy chains inside PT (option al), or null */
    private var walk: RsmState? = null

    /**
     * PT -> alloc_t | assign PT | gate_k PT tag_t (t ∈ P_k) | load_f PT FP_f.
     * [shareGate]: gate_k G_k with G_k -> PT tag_t (t ∈ P_k), one GSS node per (gate, source variable);
     * [shareLoad]: load_f LD_f with LD_f -> PT FP_f, one GSS node per (field, base variable).
     */
    protected fun buildPT(shareGate: Boolean = false, shareLoad: Boolean = false, assignLoop: Boolean = false) {
        val p0 = st(pt, "PT.start", start = true)
        val pF = st(pt, "PT.final", final = true)
        for (tag in 0 until tg.tagCount) p0.addEdge(t("alloc_$tag"), pF)
        if (assignLoop) {
            // PT -> assign* (alloc_t | gate_k PT tag_t | load_f PT FP_f): walk copy chains inside the box (no GSS call
            // per assign edge, but no sharing of the intermediate variables' results either)
            val pw = st(pt, "PT.walk")
            p0.addEdge(t("assign"), pw); pw.addEdge(t("assign"), pw)
            for (tag in 0 until tg.tagCount) pw.addEdge(t("alloc_$tag"), pF)
            walk = pw
        } else {
            val p1 = st(pt, "PT.afterAssign")
            p0.addEdge(t("assign"), p1); p1.addEdge(pt, pF)
        }
        for ((k, p) in tg.gates.withIndex()) {
            if (shareGate) {
                val gk = Nonterminal("G_$k")
                val g1 = st(pt, "PT.afterGate")
                p0.addEdge(t("gate_$k"), g1); walk?.addEdge(t("gate_$k"), g1); g1.addEdge(gk, pF)
                val q0 = st(gk, "G.start", start = true); val q1 = st(gk, "G.checkTag"); val qF = st(gk, "G.final", final = true)
                q0.addEdge(pt, q1)
                var tag = p.nextSetBit(0)
                while (tag >= 0) { q1.addEdge(t("tag_$tag"), qF); tag = p.nextSetBit(tag + 1) }
            } else {
                val g1 = st(pt, "PT.afterGate"); val g2 = st(pt, "PT.gateCheckTag")
                p0.addEdge(t("gate_$k"), g1); walk?.addEdge(t("gate_$k"), g1); g1.addEdge(pt, g2)
                var tag = p.nextSetBit(0)
                while (tag >= 0) { g2.addEdge(t("tag_$tag"), pF); tag = p.nextSetBit(tag + 1) }
            }
        }
        for (f in 0 until tg.fieldCount) {
            if (shareLoad) {
                val ld = Nonterminal("LD_$f")
                val l1 = st(pt, "PT.afterLoad")
                p0.addEdge(t("load_$f"), l1); walk?.addEdge(t("load_$f"), l1); l1.addEdge(ld, pF)
                val d0 = st(ld, "LD.start", start = true); val d1 = st(ld, "LD.baseObj"); val dF = st(ld, "LD.final", final = true)
                d0.addEdge(pt, d1); d1.addEdge(fp[f], dF)
            } else {
                val l1 = st(pt, "PT.afterLoad"); val l2 = st(pt, "PT.loadBaseObj")
                p0.addEdge(t("load_$f"), l1); walk?.addEdge(t("load_$f"), l1); l1.addEdge(pt, l2); l2.addEdge(fp[f], pF)
            }
        }
    }

    /**
     * Store/load site vertices ([sites]): one vertex s(b,f) per (base variable, field) with a store and one l(b,f) per
     * (base variable, field) with a load; edges b -stp-> s(b,f) -sval_f-> y for every store b.f = y and
     * b -ldp-> l(b,f) -lval_f-> x for every load x = b.f (the plain store_f / load_r_f edges are then not added).
     * FT(o) is then popped once into SA(o) (store sites reached by o) and once into LA(o) (load sites), instead of once
     * per field f into every FP_f(o) / FL_f(o).
     */
    open val sites: Boolean = false
    /** H8 ablation: store sites only (ss) / load sites only (sl); [sites] = both */
    open val storeSites: Boolean get() = sites
    open val loadSites: Boolean get() = sites
    private val siteIds = HashMap<Long, Int>()
    private fun site(kind: Int, b: Int, f: Int): Int =
        siteIds.getOrPut((kind.toLong() shl 62) or (b.toLong() shl 31) or f.toLong()) { tg.vertexCount + siteIds.size }

    /** FP_f -> FT store_f PT ; FL_f -> FT load_r_f   (with [sites]: FP_f -> SA sval_f PT, SA -> FT stp; FL_f -> LA lval_f, LA -> FT ldp) */
    protected fun buildFields() {
        val sa = Nonterminal("SA"); val la = Nonterminal("LA")
        if (storeSites) {
            val x0 = st(sa, "SA.start", start = true); val x1 = st(sa, "SA.baseVar"); val xF = st(sa, "SA.site", final = true)
            x0.addEdge(ft, x1); x1.addEdge(t("stp"), xF)
        }
        if (loadSites) {
            val y0 = st(la, "LA.start", start = true); val y1 = st(la, "LA.baseVar"); val yF = st(la, "LA.site", final = true)
            y0.addEdge(ft, y1); y1.addEdge(t("ldp"), yF)
        }
        for (f in 0 until tg.fieldCount) {
            if (storeSites) { // FP_f -> SA sval_f PT
                val a0 = st(fp[f], "FP.start", start = true); val a1 = st(fp[f], "FP.storeSite"); val a2 = st(fp[f], "FP.afterStore")
                val aF = st(fp[f], "FP.final", final = true)
                a0.addEdge(sa, a1); a1.addEdge(t("sval_$f"), a2); a2.addEdge(pt, aF)
            } else { // FP_f -> FT store_f PT
                val a0 = st(fp[f], "FP.start", start = true); val a1 = st(fp[f], "FP.storeBaseVar"); val a2 = st(fp[f], "FP.afterStore")
                val aF = st(fp[f], "FP.final", final = true)
                a0.addEdge(ft, a1); a1.addEdge(t("store_$f"), a2); a2.addEdge(pt, aF)
            }
            if (loadSites) { // FL_f -> LA lval_f
                val c0 = st(fl[f], "FL.start", start = true); val c1 = st(fl[f], "FL.loadSite"); val cF = st(fl[f], "FL.final", final = true)
                c0.addEdge(la, c1); c1.addEdge(t("lval_$f"), cF)
            } else { // FL_f -> FT load_r_f
                val c0 = st(fl[f], "FL.start", start = true); val c1 = st(fl[f], "FL.loadBaseVar"); val cF = st(fl[f], "FL.final", final = true)
                c0.addEdge(ft, c1); c1.addEdge(t("load_r_$f"), cF)
            }
        }
    }

    /** FT -> alloc_r_t H_t (t not an empty array), H_t -> ε | assign_r H_t | gate_r_k H_t (t ∈ P_k) | BR H_t */
    protected fun buildFT(nt: Nonterminal, name: String, emptyArrays: Boolean) {
        val f0 = st(nt, "$name.start", start = true)
        for (tag in 0 until tg.tagCount) {
            if (tg.tagEmptyArray[tag] != emptyArrays) continue // FT: bases (non-empty); VE: empty arrays as values only
            val h = st(nt, "$name.var", final = true)
            f0.addEdge(t("alloc_r_$tag"), h)
            h.addEdge(t("assign_r"), h)
            h.addEdge(br, h)
            for ((k, p) in tg.gates.withIndex()) if (p.get(tag)) h.addEdge(t("gate_r_$k"), h)
        }
    }

    /** BR -> store_r_f PT FL_f; [shareStore]: store_r_f ST_f with ST_f -> PT FL_f, one GSS node per (field, base variable) */
    protected fun buildBR(shareStore: Boolean = false) {
        val b0 = st(br, "BR.start", start = true)
        val bF = st(br, "BR.final", final = true)
        for (f in 0 until tg.fieldCount) {
            if (shareStore) {
                val stf = Nonterminal("ST_$f")
                val b1 = st(br, "BR.afterStore")
                b0.addEdge(t("store_r_$f"), b1); b1.addEdge(stf, bF)
                val s0 = st(stf, "ST.start", start = true); val s1 = st(stf, "ST.baseObj"); val sF = st(stf, "ST.final", final = true)
                s0.addEdge(pt, s1); s1.addEdge(fl[f], sF)
            } else {
                val b1 = st(br, "BR.afterStore"); val b2 = st(br, "BR.storeBaseObj")
                b0.addEdge(t("store_r_$f"), b1); b1.addEdge(pt, b2); b2.addEdge(fl[f], bF)
            }
        }
    }

    protected fun fictive(vararg starts: Nonterminal): RsmState {
        val nt = Nonterminal("fictiveStart")
        val s0 = st(nt, "S.start", start = true)
        val s1 = st(nt, "S.final", final = true)
        for (n in starts) s0.addEdge(n, s1)
        return s0
    }

    companion object {
        /**
         * "<base>[+opt...]": base v2 (start PT at variables) | ft (start FT/VE at objects); options:
         * g (shared gate nonterminal), ld (shared load nonterminal), st (shared store nonterminal),
         * s (store/load site vertices), nf (no fictive start: the PT start state is the start state; v2 only).
         */
        fun of(name: String, tg: TypedGraph): RsmDesign {
            val parts = name.split("+")
            val o = parts.drop(1).toSet()
            require(o.all { it in setOf("g", "ld", "st", "s", "ss", "sl", "nf", "al") }) { "unknown option in --rsm=$name" }
            return when (parts[0]) {
                "v2" -> V2(tg, o)
                "ft" -> FtFirst(tg, o)
                else -> error("unknown --rsm=$name (v2|ft[+g][+ld][+st][+s][+nf])")
            }
        }
    }

    /** v2 exactly as [TypedPointsToShared]: start PT at every variable */
    class V2(tg: TypedGraph, o: Set<String>) : RsmDesign(tg) {
        override val rootsFromVariables = true
        override val sites = "s" in o
        override val storeSites = "s" in o || "ss" in o
        override val loadSites = "s" in o || "sl" in o
        override val startState: RsmState
        init {
            buildPT("g" in o, "ld" in o, "al" in o); buildFields(); buildFT(ft, "FT", emptyArrays = false); buildBR("st" in o)
            startState = if ("nf" in o) pt.startState else fictive(pt)
        }
    }

    /**
     * FT-first: start at every allocation vertex and walk along value flow. The object (and its tag) is known from the
     * first step, so type gates are checked statically in the H_t state (no tag self-loop round trip); PT is called only
     * for bases of stores (BR) and of loads (via FP inside PT). Non-empty objects use FT itself (shared with field bases);
     * empty arrays use a separate box VE that never acts as a base.
     */
    class FtFirst(tg: TypedGraph, o: Set<String>) : RsmDesign(tg) {
        override val rootsFromVariables = false
        override val sites = "s" in o
        override val startState: RsmState
        private val ve = Nonterminal("VE")
        /** with nf: two parses without a fictive start (FT at non-empty objects, VE at empty arrays), results from the GSS */
        val noFictive = "nf" in o
        val ftStart: RsmState get() = ft.startState
        val veStart: RsmState get() = ve.startState
        init {
            buildPT("g" in o, "ld" in o, "al" in o); buildFields(); buildFT(ft, "FT", emptyArrays = false); buildBR("st" in o)
            buildFT(ve, "VE", emptyArrays = true)
            startState = if (noFictive) ft.startState else fictive(ft, ve)
        }
    }
}

object RsmSolve {
    class Out(val pointsTo: Map<Int, Set<Int>>, val graphMillis: Long, val solveMillis: Long, val states: Int, val roots: Int,
              val solveCpuMillis: Long = -1, val liveHeapMb: Long = -1)

    /** heap: System.gc() before building the input graph and right after parse (GLL structures still alive); liveHeapMb = difference */
    fun solve(tg0: TypedGraph, design: String, vars: Collection<Int>, reduce: Boolean, reachOnly: Boolean, profile: Boolean,
              heap: Boolean = false): Out {
        val rt = Runtime.getRuntime()
        fun used(): Long { System.gc(); System.gc(); return rt.totalMemory() - rt.freeMemory() }
        val (tg, rep) = if (reduce) tg0.reduced() else tg0 to IntArray(tg0.vertexCount) { it }
        val heap0 = if (heap) used() else 0L
        val t0 = System.nanoTime()
        val d = RsmDesign.of(design, tg)
        val repVars = vars.map { rep[it] }.distinct()
        val input = d.inputGraph(d.starts(repVars))
        val t1 = System.nanoTime()
        val mx = java.lang.management.ManagementFactory.getThreadMXBean()
        val c1 = mx.currentThreadCpuTime
        val gll = Gll.gll(d.startState, input, reachabilityOnly = reachOnly)
        var roots: Set<org.ucfs.sppf.node.RangeSppfNode<Int>> = gll.parse()
        if (d is RsmDesign.FtFirst && d.noFictive) {
            // results = ranges popped from the GSS nodes (FT.start, o) of the start objects; then VE for empty arrays
            fun popped(g: Gll<Int, TerminalInputLabel>, st: RsmState) =
                g.ctx.gss.nodes.keys.filter { it.rsm === st }.flatMap { it.popped }
            val all = HashSet(popped(gll, d.ftStart))
            val empties = (0 until tg.vertexCount).filter { tg.isAlloc(it) && tg.tagEmptyArray[tg.tagOf[it]] }
            if (empties.isNotEmpty()) {
                val in2 = d.inputGraph(empties)
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
        return Out(res, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, d.states, roots.size, (c2 - c1) / 1_000_000, live)
    }
}
