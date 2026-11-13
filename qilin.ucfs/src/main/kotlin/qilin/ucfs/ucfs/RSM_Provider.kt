package qilin.ucfs.ucfs

import qilin.ucfs.graph.TypedGraph
import org.ucfs.input.TerminalInputLabel
import org.ucfs.rsm.RsmState
import org.ucfs.rsm.symbol.Nonterminal
import org.ucfs.rsm.symbol.Term
import java.util.IdentityHashMap

/** Builds fresh RSMs for active UCFS analyses. Graph preparation and solver execution stay outside this provider. */
object RSM_Provider {
    fun plain(fieldCount: Int): PlainRsm = PlainRsm(fieldCount)
    fun typed(graph: TypedGraph): TypedRsm = TypedRsm(graph)
    fun design(name: String, graph: TypedGraph): TypedDesign = TypedDesign.of(name, graph)
    fun dyck(callSiteCount: Int): DyckRsm = DyckRsm(callSiteCount)

    /**
     * Field-sensitive context-insensitive points-to as CFL-reachability (Andersen-style "flowsTo",
     * Sridharan et al. OOPSLA'05; in the inverse "pointsTo" form used by UCFS PR #49):
     *
     *   PointsTo -> ( assign | load_f Alias store_f )* alloc        (for every field f)
     *   FlowsTo  -> alloc_r ( assign_r | store_f_r Alias load_f_r )*
     *   Alias    -> PointsTo FlowsTo
     *
     * Graph orientation (from a Qilin value-flow edge src -> dst):
     *   ALLOC o->x  : x -alloc-> o,  o -alloc_r-> x
     *   ASSIGN y->x : x -assign-> y, y -assign_r-> x
     *   STORE y->b.f: b -store_f-> y, y -store_f_r-> b
     *   LOAD b.f->x : x -load_f-> b,  b -load_f_r-> x
     * so PointsTo(x, o) holds iff object o may flow into variable x.
     *
     * The RSM is built by hand (one box state per field) instead of with the Grammar DSL:
     * the DSL left-folds thousands of alternatives and overflows the stack (skill ucfs, gotcha 6).
     */
    class PlainRsm internal constructor(val fieldCount: Int) {
        val pointsTo = Nonterminal("PointsTo")
        val flowsTo = Nonterminal("FlowsTo")
        val alias = Nonterminal("Alias")
        private val fictive = Nonterminal("fictiveStart")

        val alloc = Term("alloc")
        val allocR = Term("alloc_r")
        val assign = Term("assign")
        val assignR = Term("assign_r")
        private val loads = Array(fieldCount) { Term("load_$it") }
        private val loadsR = Array(fieldCount) { Term("load_${it}_r") }
        private val stores = Array(fieldCount) { Term("store_$it") }
        private val storesR = Array(fieldCount) { Term("store_${it}_r") }

        fun load(f: Int) = loads[f]
        fun loadR(f: Int) = loadsR[f]
        fun store(f: Int) = stores[f]
        fun storeR(f: Int) = storesR[f]

        /** Fictive start state; pass it to `Gll.gll`. */
        val startState: RsmState

        init {
            // PointsTo box
            val p0 = RsmState(pointsTo, isStart = true)
            val pFinal = RsmState(pointsTo, isFinal = true)
            pointsTo.startState = p0
            p0.addEdge(assign, p0)
            p0.addEdge(alloc, pFinal)
            for (f in 0 until fieldCount) {
                val afterLoad = RsmState(pointsTo)
                val afterAlias = RsmState(pointsTo)
                p0.addEdge(loads[f], afterLoad)
                afterLoad.addEdge(alias, afterAlias)
                afterAlias.addEdge(stores[f], p0)
            }
            // FlowsTo box
            val f0 = RsmState(flowsTo, isStart = true)
            val f1 = RsmState(flowsTo, isFinal = true)
            flowsTo.startState = f0
            f0.addEdge(allocR, f1)
            f1.addEdge(assignR, f1)
            for (f in 0 until fieldCount) {
                val afterStore = RsmState(flowsTo)
                val afterAlias = RsmState(flowsTo)
                f1.addEdge(storesR[f], afterStore)
                afterStore.addEdge(alias, afterAlias)
                afterAlias.addEdge(loadsR[f], f1)
            }
            // Alias box
            val a0 = RsmState(alias, isStart = true)
            val a1 = RsmState(alias)
            val a2 = RsmState(alias, isFinal = true)
            alias.startState = a0
            a0.addEdge(pointsTo, a1)
            a1.addEdge(flowsTo, a2)
            // fictive start: fictiveStart -> PointsTo (Gll.gll takes outgoingEdges[0] as the final state)
            val s0 = RsmState(fictive, isStart = true)
            val s1 = RsmState(fictive, isFinal = true)
            fictive.startState = s0
            s0.addEdge(pointsTo, s1)
            startState = s0
        }

        /** Interned input labels (one object per terminal). */
        private val labelCache = HashMap<Term<String>, TerminalInputLabel>()
        fun label(t: Term<String>): TerminalInputLabel = labelCache.getOrPut(t) { TerminalInputLabel(t) }
    }

    /**
     * Typed points-to grammar, version 2 ("shared"): Spark's
     * filters as gates over a [TypedGraph], shaped so that GLL shares sub-results the way Spark shares
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
    class TypedRsm internal constructor(tg: TypedGraph) {
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

    }

    /**
     * Hand-built RSM designs for the typed points-to language over a [TypedGraph] (M13: "build the RSM directly and design
     * the optimal one for the concrete analysis"). Every design accepts the same language as [TypedPointsToShared] (v2);
     * they differ in box structure, in which nonterminal is the start symbol and in the start vertices, which changes the
     * GLL work (descriptors, GSS nodes, popped results) but not the answer. Every state carries a role label for
     * [RsmProfile].
     */
    abstract class TypedDesign(val tg: TypedGraph) {
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
            fun of(name: String, tg: TypedGraph): TypedDesign {
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
        class V2(tg: TypedGraph, o: Set<String>) : TypedDesign(tg) {
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
        class FtFirst(tg: TypedGraph, o: Set<String>) : TypedDesign(tg) {
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

    /** Call-site Dyck grammar; heap/static edges reset the call stack. */
    class DyckRsm internal constructor(sites: Int) {
        private val terms = HashMap<String, TerminalInputLabel>()
        fun label(s: String): TerminalInputLabel = terms.getOrPut(s) { TerminalInputLabel(Term(s)) }
        private fun t(s: String) = label(s).terminal
        var states = 0
            private set
        private fun st(nt: Nonterminal, start: Boolean = false, final: Boolean = false) =
            RsmState(nt, isStart = start, isFinal = final).also { states++; if (start) nt.startState = it }
        val startState: RsmState
        init {
            // UCFS indexes terminal transitions (one edge per terminal and state, D11): the RSM is deterministic on terminals and
            // branches only on nonterminal calls; no nonterminal derives the empty string (path ends are final states instead).
            // P — a path in X mode (unmatched returns allowed); T — the rest after an unmatched call (N mode); B — a balanced segment.
            //   P -> item | item P,  item = a | h | r_i | c_i r_i | c_i B r_i;   P -> c_i | c_i T
            //   T -> titem | titem T | h | h P,  titem = a | c_i r_i | c_i B r_i | c_i;      B -> bitem | bitem B,  bitem = a | c_i r_i | c_i B r_i
            val p = Nonterminal("P"); val tn = Nonterminal("T"); val b = Nonterminal("B")
            val x = st(p, start = true); val x1 = st(p, final = true); val pf = st(p, final = true)
            val q0 = st(tn, start = true); val q1 = st(tn, final = true); val qh = st(tn, final = true); val qf = st(tn, final = true)
            val s0 = st(b, start = true); val s1 = st(b, final = true); val s2 = st(b, final = true)
            x.addEdge(t("a"), x1); x.addEdge(t("h"), x1); x1.addEdge(p, pf)
            q0.addEdge(t("a"), q1); q0.addEdge(t("h"), qh); q1.addEdge(tn, qf); qh.addEdge(p, qf)
            s0.addEdge(t("a"), s1); s1.addEdge(b, s2)
            for (i in 0 until sites) {
                val c = t("c$i"); val r = t("r$i")
                x.addEdge(r, x1)                                            // unmatched return
                val y = st(p, final = true); val z = st(p)                  // after c_i in X mode: unmatched (end or T) or matched
                x.addEdge(c, y); y.addEdge(tn, pf); y.addEdge(r, x1); y.addEdge(b, z); z.addEdge(r, x1)
                val u = st(tn, final = true); val w = st(tn)                // after c_i in N mode
                q0.addEdge(c, u); u.addEdge(tn, qf); u.addEdge(r, q1); u.addEdge(b, w); w.addEdge(r, q1)
                val bv = st(b); val bv2 = st(b)                             // matched call inside a balanced segment
                s0.addEdge(c, bv); bv.addEdge(r, s1); bv.addEdge(b, bv2); bv2.addEdge(r, s1)
            }
            // Gll.gll takes a fictive start state whose only edge (a call of the start nonterminal) leads to the accepting state
            val sNt = Nonterminal("S"); val sStart = st(sNt, start = true); val sFinal = st(sNt, final = true)
            sStart.addEdge(p, sFinal)
            startState = sStart
        }
    }
}
