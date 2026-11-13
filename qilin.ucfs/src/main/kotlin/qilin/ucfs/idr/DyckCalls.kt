package qilin.ucfs.idr

import qilin.ucfs.graph.CiPag
import qilin.ucfs.results.DyckResult
import qilin.ucfs.graph.EdgeKind
import qilin.ucfs.graph.EdgeOrigin
import qilin.ucfs.QilinSession
import qilin.ucfs.graph.TypedGraph

import org.ucfs.input.InputGraph
import org.ucfs.input.TerminalInputLabel
import org.ucfs.parser.Gll
import org.ucfs.rsm.RsmState
import org.ucfs.rsm.symbol.Nonterminal
import org.ucfs.rsm.symbol.Term
import qilin.core.PTA
import qilin.core.pag.GlobalVarNode
import qilin.core.pag.VarNode
import qilin.core.pag.VirtualCallSite
import qilin.util.PTAUtils
import soot.Type
import soot.jimple.toolkits.callgraph.Edge
import soot.Unit as SootUnit
import java.lang.management.ManagementFactory

/**
 * Variant A (M15, GOAL_2 §4): call-site sensitivity as one context-free language solved by UCFS — Dyck parentheses of call
 * sites with exact recursion over a context-insensitive heap (heap vertices h(o,f) for o in Spark's points-to set of the base,
 * the heap-CI encoding of M7/D25). The call stack is reset at heap and static-field vertices:
 *
 *   P -> R | P h R          R -> X N          X -> X B | X r_i | ε          N -> N B | N c_i | ε
 *   B -> B B | c_i B r_i | a | ε
 *
 * a: allocation / intra-procedural assignment; c_i: argument and receiver passing at call site i; r_i: return at site i;
 * h: an edge into or out of a heap vertex or a static field (GlobalVarNode). A pair (x, o) is in the result iff some path
 * from o to x is in the language. No type filters (as heap-CI+pb): the result is compared with cfl-idr heap-CI+pb (E1) and
 * with Spark ∩ (E2).
 */
class DyckCalls private constructor(
    val pag: CiPag,
    val vertexCount: Int,
    val edges: List<Triple<Int, String, Int>>,
    val sites: Int,
    /** start vertices; null — the allocation vertices of [pag] */
    private val starts: IntArray? = null,
    /** typed product graph (M17): vertex -> base vertex of [pag] (or heap vertex); null — vertices are base vertices */
    private val baseOf: IntArray? = null,
    /** reduction (M17): vertex -> representative whose answer it shares; null — no reduction */
    private val rep: IntArray? = null,
) {
    /** A filter on an edge, decided by the tag (type) of the object that flows along it (M17, Spark's filters). */
    private sealed class Gate {
        /** castNeverFails(type(o), [type]): Spark's filter at a variable of declared type [type] */
        class TypeF(val type: Type) : Gate()
        /** dispatch at [site] selects [tgt] for the type of o: receiver -> this */
        class Disp(val site: VirtualCallSite, val tgt: soot.SootMethod) : Gate()
    }

    companion object {
        /** Methods Zipper finds precision-critical on the solved Spark [pta] (needs `CallDetails` during Spark, see QilinSession). */
        fun zipperSelection(pta: PTA): Set<soot.SootMethod> {
            val pcm = HashSet<soot.SootMethod>()
            qilin.pta.toolkits.zipper.Main.run(pta, pcm, false)
            return pcm
        }

        /** [selected]: methods whose calls get parentheses (variant A′, M16); calls of other methods are plain flow `a`; null — every method (variant A). */
        fun build(pta: PTA, pag: CiPag, selected: ((soot.SootMethod) -> Boolean)? = null): DyckCalls {
            val edges = LinkedHashSet<Triple<Int, String, Int>>()
            val (nv, sites) = collect(pta, pag, selected) { u, l, v, _ -> edges.add(Triple(u, l, v)) }
            return DyckCalls(pag, nv, edges.toList(), sites)
        }

        /**
         * Variant A/A′ with Spark's filters (M17): every edge into a variable filters by the declared type of the target
         * (castNeverFails, as Spark's propagation), receiver -> this by Spark's dispatch at the call site; stores into heap vertices
         * are not filtered (field nodes without -pae). A filter depends on the object that flows, so the graph is the product
         * "vertex × tag" (tag = type of the allocation) over the pairs reachable from (o, tag(o)) along admitted edges.
         */
        fun buildTyped(pta: PTA, pag: CiPag, selected: ((soot.SootMethod) -> Boolean)? = null): DyckCalls {
            // base edges grouped by (src, label, dst) with the filters of their origins (any admits)
            val grouped = LinkedHashMap<Triple<Int, String, Int>, MutableList<Gate?>>()
            val (nb, sites) = collect(pta, pag, selected) { u, l, v, gt -> grouped.getOrPut(Triple(u, l, v)) { ArrayList(1) }.add(gt) }
            val outs = arrayOfNulls<MutableList<Pair<Triple<Int, String, Int>, List<Gate?>>>>(nb)
            for ((k, gs) in grouped) (outs[k.first] ?: ArrayList<Pair<Triple<Int, String, Int>, List<Gate?>>>().also { outs[k.first] = it }).add(k to gs)
            val tagIds = HashMap<Type, Int>(); val tagTypes = ArrayList<Type>()
            fun tagOf(t: Type) = tagIds.getOrPut(t) { tagTypes.add(t); tagTypes.size - 1 }
            val typeCache = HashMap<Type, Pair<java.util.BitSet, java.util.BitSet>>()   // type -> (known tags, admitted tags)
            val dispCache = HashMap<Gate.Disp, Pair<java.util.BitSet, java.util.BitSet>>()
            fun admits(g: Gate?, tag: Int): Boolean = when (g) {
                null -> true
                is Gate.TypeF -> { val (k, a) = typeCache.getOrPut(g.type) { java.util.BitSet() to java.util.BitSet() }
                    if (!k[tag]) { k.set(tag); if (PTAUtils.castNeverFails(tagTypes[tag], g.type)) a.set(tag) }; a[tag] }
                is Gate.Disp -> { val (k, a) = dispCache.getOrPut(g) { java.util.BitSet() to java.util.BitSet() }
                    if (!k[tag]) { k.set(tag); if (g.tgt in TypedGraph.dispatchTargets(tagTypes[tag], g.site)) a.set(tag) }; a[tag] }
            }
            val ids = HashMap<Long, Int>(); val baseOf = IntList(); val tagOfP = IntList()
            fun pid(v: Int, tag: Int): Int = ids.getOrPut((v.toLong() shl 32) or tag.toLong()) { baseOf.add(v); tagOfP.add(tag); baseOf.size - 1 }
            val starts = IntList()
            for (o in pag.graph.allocIds()) starts.add(pid(o, tagOf(pag.nodes[o].type)))
            val edges = ArrayList<Triple<Int, String, Int>>()
            var i = 0
            while (i < baseOf.size) {
                val v = baseOf[i]; val tag = tagOfP[i]
                for ((k, gs) in outs[v] ?: emptyList()) if (gs.any { admits(it, tag) }) edges.add(Triple(i, k.second, pid(k.third, tag)))
                i++
            }
            return DyckCalls(pag, baseOf.size, edges, sites, starts.toArray(), baseOf.toArray())
        }

        /** Edges of A/A′ over the base graph (+ heap vertices) with their Spark filters; returns (vertex count, call sites). */
        private fun collect(pta: PTA, pag: CiPag, selected: ((soot.SootMethod) -> Boolean)?, emit: (Int, String, Int, Gate?) -> Unit): Pair<Int, Int> {
            val n = pag.nodes.size
            var nv = n
            val heap = HashMap<Pair<Int, Int>, Int>()
            fun h(o: Int, f: Int) = heap.getOrPut(o to f) { nv++ }
            fun global(v: Int) = pag.nodes[v] is GlobalVarNode
            fun filter(v: Int) = Gate.TypeF(pag.nodes[v].type)
            fun objectsOf(b: Int): List<Int> = pta.reachingObjects(pag.nodes[b]).toCIPointsToSet().toCollection()
                .filter { !PTAUtils.isEmptyArray(it) }.map { pag.idOf(it) }.filter { it >= 0 }
            for (e in pag.graph.edges) when (e.kind) {
                EdgeKind.ALLOC -> emit(e.src, if (global(e.dst)) "h" else "a", e.dst, filter(e.dst))
                EdgeKind.ASSIGN -> if (e.origin == EdgeOrigin.INTRA || e.origin == EdgeOrigin.ROOT || e.origin == EdgeOrigin.FINALIZER)
                    emit(e.src, if (global(e.src) || global(e.dst)) "h" else "a", e.dst, filter(e.dst))
                EdgeKind.STORE -> for (o in objectsOf(e.dst)) emit(e.src, "h", h(o, e.field), null)
                EdgeKind.LOAD -> for (o in objectsOf(e.src)) emit(h(o, e.field), "h", e.dst, filter(e.dst))
            }
            // interprocedural edges with call-site labels (as IdrExport: one site per call statement)
            val siteIds = LinkedHashMap<SootUnit, Int>()
            val vsites = HashMap<SootUnit, VirtualCallSite>()
            val qpag = pta.pag
            fun id(nd: qilin.core.pag.Node?): Int = if (nd == null) -1 else pag.idOf(nd)
            for (e in pta.callGraph) {
                val s = e.srcStmt() ?: continue
                if (!s.containsInvokeExpr()) continue
                val bracketed = selected == null || selected(e.tgt())
                val c = if (bracketed) siteIds.getOrPut(s) { siteIds.size } else -1
                val srcnf = qpag.getMethodPAG(e.src()).nodeFactory()
                val tgtnf = qpag.getMethodPAG(e.tgt()).nodeFactory()
                val ie = s.invokeExpr
                val tgt = e.tgt()
                for (i in 0 until minOf(ie.argCount, tgt.parameterCount)) {
                    val a = ie.getArg(i)
                    if (a.type !is soot.RefLikeType || a is soot.jimple.NullConstant || tgt.getParameterType(i) !is soot.RefLikeType) continue
                    val u = id(srcnf.getNode(a)); val p = id(tgtnf.caseParm(i))
                    if (u >= 0 && p >= 0) emit(u, if (bracketed) "c$c" else "a", p, filter(p))
                }
                if (ie is soot.jimple.InstanceInvokeExpr) {
                    val u = id(srcnf.getNode(ie.base)); val t = id(tgtnf.caseThis())
                    if (u >= 0 && t >= 0) {
                        val site = vsites.getOrPut(s) {
                            VirtualCallSite(srcnf.getNode(ie.base) as VarNode, s, pta.parameterize(e.src(), pta.emptyContext()), ie,
                                ie.methodRef.subSignature, Edge.ieToKind(ie))
                        }
                        emit(u, if (bracketed) "c$c" else "a", t, Gate.Disp(site, tgt))
                    }
                }
                if (s is soot.jimple.AssignStmt && s.leftOp.type is soot.RefLikeType && tgt.returnType is soot.RefLikeType) {
                    val r = id(tgtnf.caseRet()); val d = id(srcnf.getNode(s.leftOp))
                    if (r >= 0 && d >= 0) emit(r, if (bracketed) "r$c" else "a", d, filter(d))
                }
            }
            return nv to siteIds.size
        }
    }

    /**
     * Exact reduction (M17; offline variable substitution, `Rountev2000Off`): (1) a strongly connected component of `a` edges collapses
     * to one vertex; (2) a vertex whose only incoming edge is `u -a-> v` is merged into u. Both are exact for the language of A: `a` is
     * a balanced segment (B -> a), so inserting or deleting `a` anywhere in a word keeps it in or out of the language, and a merged
     * vertex has the answer of its representative. Start vertices are never merged, nor merged into; `a` self-loops are dropped.
     */
    fun reduced(): DyckCalls {
        val n = vertexCount
        val isStart = BooleanArray(n); for (o in startVertices()) isStart[o] = true
        val r = IntArray(n) { it }
        fun find(x: Int): Int { var y = x; while (r[y] != y) y = r[y]; var z = x; while (r[z] != y) { val nx = r[z]; r[z] = y; z = nx }; return y }
        // (1) SCCs of `a` edges (iterative Tarjan), start vertices excluded
        val succ = arrayOfNulls<IntList>(n)
        for ((u, l, v) in edges) if (l == "a" && u != v && !isStart[u] && !isStart[v]) (succ[u] ?: IntList().also { succ[u] = it }).add(v)
        val index = IntArray(n) { -1 }; val low = IntArray(n); val onStack = BooleanArray(n)
        val stack = IntList(); var counter = 0; val callV = IntList(); val callI = IntList()
        for (s0 in 0 until n) {
            if (index[s0] >= 0 || succ[s0] == null) continue
            callV.add(s0); callI.add(0); index[s0] = counter; low[s0] = counter; counter++; stack.add(s0); onStack[s0] = true
            while (callV.size > 0) {
                val v = callV.last(); val i = callI.last(); val sv = succ[v]
                if (sv != null && i < sv.size) {
                    callI.setLast(i + 1)
                    val w = sv[i]
                    if (index[w] < 0) { index[w] = counter; low[w] = counter; counter++; stack.add(w); onStack[w] = true; callV.add(w); callI.add(0) }
                    else if (onStack[w]) low[v] = minOf(low[v], index[w])
                } else {
                    callV.removeLast(); callI.removeLast()
                    if (callV.size > 0) { val p = callV.last(); low[p] = minOf(low[p], low[v]) }
                    if (low[v] == index[v]) while (true) { val w = stack.removeLast(); onStack[w] = false; r[w] = v; if (w == v) break }
                }
            }
        }
        // (2) a single incoming edge, labelled `a`, on the condensed graph
        val inCount = IntArray(n); val inA = IntArray(n) { -1 }
        for ((u0, l, v0) in edges) {
            val u = find(u0); val v = find(v0)
            if (l == "a" && u == v) continue
            inCount[v]++; if (l == "a") inA[v] = u else inA[v] = -1
        }
        for (v in 0 until n) if (find(v) == v && !isStart[v] && inCount[v] == 1 && inA[v] >= 0 && !isStart[inA[v]]) r[v] = inA[v]
        for (v in 0 until n) find(v)
        val out = LinkedHashSet<Triple<Int, String, Int>>()
        for ((u, l, v) in edges) { val su = r[u]; val sv = r[v]; if (l == "a" && su == sv) continue; out.add(Triple(su, l, sv)) }
        // compose with an earlier reduction: vertex -> representative of its representative
        val composed = if (rep == null) r else IntArray(rep.size) { r[rep[it]] }
        return DyckCalls(pag, n, out.toList(), sites, starts, baseOf, composed)
    }

    /** Vertices of the graph that carry an edge (after a reduction, merged vertices are isolated). */
    fun activeVertices(): Int { val b = java.util.BitSet(vertexCount); for ((u, _, v) in edges) { b.set(u); b.set(v) }; return b.cardinality() }

    private fun startVertices(): IntArray = starts ?: pag.graph.allocIds().toIntArray()



    /** Solves the language from every allocation vertex; pairs (variable, object) for the base variables of the CI graph. */
    fun solve(reachOnly: Boolean = true, heap: Boolean = false): DyckResult {
        val tm0 = System.nanoTime()
        val terms = HashMap<String, TerminalInputLabel>()
        fun label(s: String) = terms.getOrPut(s) { TerminalInputLabel(Term(s)) }
        fun t(s: String) = label(s).terminal
        var states = 0
        fun st(nt: Nonterminal, start: Boolean = false, final: Boolean = false) =
            RsmState(nt, isStart = start, isFinal = final).also { states++; if (start) nt.startState = it }
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
        val g = InputGraph<Int, TerminalInputLabel>()
        for (v in 0 until vertexCount) g.addVertex(v)
        for ((u, l, v) in edges) g.addEdge(u, label(l), v)
        for (o in startVertices()) g.addStartVertex(o)
        val mx = ManagementFactory.getMemoryMXBean()
        fun used(): Long { System.gc(); return mx.heapMemoryUsage.used }
        val heap0 = if (heap) used() else 0L
        val tm1 = System.nanoTime()
        val roots = Gll.gll(sStart, g, reachabilityOnly = reachOnly).parse()
        val tm2 = System.nanoTime()
        val live = if (heap) (used() - heap0) / (1024 * 1024) else -1L
        val n = pag.nodes.size
        fun base(v: Int) = baseOf?.get(v) ?: v
        fun isQuery(v: Int) = base(v).let { it < n && !pag.graph.isAlloc[it] }
        // answers per vertex of this graph; reduced vertices take the answer of their representative
        val byVertex = HashMap<Int, MutableSet<Int>>()
        for (r0 in roots) {
            val range = r0.inputRange ?: continue
            val o = range.from; val v = range.to
            if (rep == null && !isQuery(v)) continue
            byVertex.getOrPut(v) { HashSet() }.add(base(o))
        }
        val res = HashMap<Int, MutableSet<Int>>()
        for (v in 0 until vertexCount) {
            if (!isQuery(v)) continue
            val os = byVertex[rep?.get(v) ?: v] ?: continue
            res.getOrPut(base(v)) { HashSet() }.addAll(os)
        }
        return DyckResult(res, (tm1 - tm0) / 1_000_000, (tm2 - tm1) / 1_000_000, states, roots.size, live)
    }
}

/** Minimal growable int list (no boxing). */
internal class IntList {
    private var a = IntArray(4); var size = 0; private set
    fun add(x: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = x }
    operator fun get(i: Int) = a[i]
    fun last() = a[size - 1]
    fun setLast(x: Int) { a[size - 1] = x }
    fun removeLast(): Int = a[--size]
    fun toArray(): IntArray = a.copyOf(size)
}
