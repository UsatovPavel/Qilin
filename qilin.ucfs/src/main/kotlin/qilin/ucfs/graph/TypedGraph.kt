package qilin.ucfs.graph

import qilin.CoreConfig
import qilin.core.PTA
import qilin.core.pag.AllocNode
import qilin.core.pag.ArrayElement
import qilin.core.pag.LocalVarNode
import qilin.core.pag.Node
import qilin.core.pag.VarNode
import qilin.core.pag.VirtualCallSite
import qilin.util.PTAUtils
import soot.RefType
import soot.Scene
import soot.SootField
import soot.Type
import soot.jimple.InstanceInvokeExpr
import soot.jimple.SpecialInvokeExpr
import soot.jimple.toolkits.callgraph.Edge
import java.util.BitSet

/**
 * The CI constraint graph with Spark's filters made explicit as edge labels ("gates"), so that a CFL
 * grammar can reproduce Spark's type filtering and virtual dispatch exactly.
 *
 * Spark filters an object o entering a variable v by `castNeverFails(type(o), type(v))` (Solver.propagatePTS)
 * and passes a receiver object to `this` of a callee only if dispatch selects that callee
 * (CallGraphBuilder.dispatch/addVirtualEdge). An object type is abstracted by a *tag*: its type and whether
 * it is an empty array (Spark never loads/stores on empty arrays, Solver.disallowStoreOrLoadOn).
 *
 *  - ALLOC o -> x is kept only if the filter at x admits o (static check, exact);
 *  - ASSIGN u -> v is a plain edge if every object admitted at u is admitted at v (type(u) <: type(v)),
 *    otherwise a gate with predicate {tags admitted at v};
 *  - LOAD b.f -> x is plain if every store into f has a source type <: type(x), otherwise gated
 *    (a shadow vertex x~ carries the load: x -gate-> x~ -load_f-> b);
 *  - STORE is plain (field nodes o.f are not filtered without -pae);
 *  - RECV_THIS r -> this_m is a gate with predicate {tags t : dispatch(t, call site) selects m}.
 * Gate predicates are bit sets over tags; equal predicates share one gate id.
 */
class TypedGraph private constructor(
    val base: CiPag,
    /** vertex count = base vertices + shadow vertices (shadow i has id base.nodes.size + i) */
    val vertexCount: Int,
    val tagOf: IntArray,               // per base vertex: tag id for allocation vertices, -1 otherwise
    val tagTypes: List<Type>,
    val tagEmptyArray: BooleanArray,
    val gates: List<BitSet>,           // gate id -> admitted tags
    val gateKinds: List<String>,       // gate id -> description of its first origin (for reports)
    val edges: List<TEdge>,
    val fieldCount: Int,
) {
    enum class L { ASSIGN, GATE, LOAD, STORE, ALLOC }

    /** Spark filters that the typed graph reproduces; each can be switched off for ablation (M4 D4.2). */
    enum class Filter {
        /** drop `o -> x` if castNeverFails(type(o), type(x)) is false */
        ALLOC,
        /** gate on assign/cast/param edges u -> v when type(u) is not a subtype of type(v) */
        ASSIGN,
        /** gate on loads whose stored values may not fit the target type */
        LOAD,
        /** receiver -> this only for objects whose dispatch selects the callee */
        DISPATCH,
        /** no load/store through empty arrays (Solver.disallowStoreOrLoadOn) */
        EMPTY_ARRAY,
    }

    /** An edge in value-flow direction src -> dst; `arg` = field for LOAD/STORE, gate id for GATE, tag for ALLOC. */
    data class TEdge(val kind: L, val src: Int, val dst: Int, val arg: Int)

    val tagCount get() = tagTypes.size

    /** Allocation edges dropped because Spark's filter at the target rejects the object. */
    var droppedAllocs = 0
        internal set

    companion object {
        fun build(pta: PTA, pag: CiPag, off: Set<Filter> = emptySet()): TypedGraph {
            check(!CoreConfig.v().ptaConfig.preciseArrayElement) { "-pae (typed field nodes) is not supported" }
            val g = pag.graph
            val n = g.vertexCount
            // tags
            val tagIds = HashMap<Pair<Type, Boolean>, Int>()
            val tagTypes = ArrayList<Type>()
            val tagEmpty = ArrayList<Boolean>()
            val tagOf = IntArray(n) { -1 }
            for (v in 0 until n) {
                val a = pag.nodes[v] as? AllocNode ?: continue
                val key = a.type to (Filter.EMPTY_ARRAY !in off && PTAUtils.isEmptyArray(a))
                tagOf[v] = tagIds.getOrPut(key) { tagTypes.add(a.type); tagEmpty.add(key.second); tagTypes.size - 1 }
            }
            val tagCount = tagTypes.size
            fun admitted(t: Type): BitSet {
                val b = BitSet(tagCount)
                for (i in 0 until tagCount) if (PTAUtils.castNeverFails(tagTypes[i], t)) b.set(i)
                return b
            }
            val admittedCache = HashMap<Type, BitSet>()
            fun admittedC(t: Type) = admittedCache.getOrPut(t) { admitted(t) }

            val gateIds = HashMap<BitSet, Int>()
            val gates = ArrayList<BitSet>()
            val gateKinds = ArrayList<String>()
            fun gate(p: BitSet, kind: String): Int = gateIds.getOrPut(p) { gates.add(p); gateKinds.add(kind); gates.size - 1 }

            fun typeOf(v: Int): Type = pag.nodes[v].type
            // source types of stores per field
            val storeSrcTypes = HashMap<Int, MutableSet<Type>>()
            for (e in g.edges) if (e.kind == EdgeKind.STORE) storeSrcTypes.getOrPut(e.field) { HashSet() }.add(typeOf(e.src))

            // RECV_THIS predicates: from the call graph edges of each (receiver, this) pair
            val recvPred = HashMap<Pair<Int, Int>, BitSet>()
            for (e: Edge in pta.callGraph) {
                val ie = e.srcStmt().invokeExpr as? InstanceInvokeExpr ?: continue
                val srcnf = pta.pag.getMethodPAG(e.src()).nodeFactory()
                val recv = srcnf.getNode(ie.base) as VarNode
                val thisNode = pta.pag.getMethodPAG(e.tgt()).nodeFactory().caseThis()
                val r = pag.idOf(recv)
                val th = pag.idOf(thisNode)
                check(r >= 0 && th >= 0) { "receiver/this missing in CiPag for $e" }
                val site = VirtualCallSite(
                    recv, e.srcStmt(), pta.parameterize(e.src(), pta.emptyContext()), ie,
                    ie.methodRef.subSignature, Edge.ieToKind(ie)
                )
                val p = recvPred.getOrPut(r to th) { BitSet(tagCount) }
                val recvAdmits = admittedC(recv.type)
                var t = recvAdmits.nextSetBit(0)
                while (t >= 0) {
                    if (dispatchSelects(tagTypes[t], site, e.tgt())) p.set(t)
                    t = recvAdmits.nextSetBit(t + 1)
                }
            }

            val edges = LinkedHashSet<TEdge>()
            val shadow = HashMap<Pair<Int, Int>, Int>() // (x, gate) -> shadow vertex
            var dropped = 0
            for (e in g.edges) {
                when (e.kind) {
                    EdgeKind.ALLOC -> {
                        val t = tagOf[e.src]
                        if (Filter.ALLOC in off || PTAUtils.castNeverFails(tagTypes[t], typeOf(e.dst))) edges.add(TEdge(L.ALLOC, e.src, e.dst, t)) else dropped++
                    }
                    EdgeKind.ASSIGN -> {
                        if (e.origin == EdgeOrigin.RECV_THIS && Filter.DISPATCH !in off) {
                            val p = recvPred.getValue(e.src to e.dst)
                            edges.add(TEdge(L.GATE, e.src, e.dst, gate(p, "dispatch")))
                        } else if (Filter.ASSIGN in off || PTAUtils.castNeverFails(typeOf(e.src), typeOf(e.dst))) {
                            edges.add(TEdge(L.ASSIGN, e.src, e.dst, -1))
                        } else {
                            edges.add(TEdge(L.GATE, e.src, e.dst, gate(admittedC(typeOf(e.dst)), "assign-filter:" + kindOfTarget(pag.nodes[e.dst]))))
                        }
                    }
                    EdgeKind.STORE -> edges.add(TEdge(L.STORE, e.src, e.dst, e.field))
                    EdgeKind.LOAD -> {
                        val dt = typeOf(e.dst)
                        val srcTypes = storeSrcTypes[e.field] ?: emptySet()
                        if (Filter.LOAD in off || srcTypes.all { PTAUtils.castNeverFails(it, dt) }) {
                            edges.add(TEdge(L.LOAD, e.src, e.dst, e.field))
                        } else {
                            val k = gate(admittedC(dt), "load-filter:" + if (pag.fields[e.field] is ArrayElement) "array" else "field")
                            val s = shadow.getOrPut(e.dst to k) { n + shadow.size }
                            edges.add(TEdge(L.LOAD, e.src, s, e.field))      // b.f -> x~
                            edges.add(TEdge(L.GATE, s, e.dst, k))            // x~ -> x (filtered at x)
                        }
                    }
                }
            }
            return TypedGraph(pag, n + shadow.size, tagOf, tagTypes, tagEmpty.toBooleanArray(), gates, gateKinds,
                edges.toList(), g.fieldCount).also { it.droppedAllocs = dropped }
        }

        /** Same decision as CallGraphBuilder.dispatch + PTAUtils.dispatch for one receiver type and one target. */
        /** All targets Spark's dispatch selects for one receiver type (CallGraphBuilder.dispatch + addVirtualEdge). */
        internal fun dispatchTargets(type: Type, site: VirtualCallSite): Set<soot.SootMethod> {
            val res = HashSet<soot.SootMethod>()
            val targets = PTAUtils.dispatch(type, site)
            while (targets.hasNext()) {
                val m = targets.next()
                if (site.iie() is SpecialInvokeExpr && !Scene.v().fastHierarchy.canStoreType(type, m.declaringClass.type)) continue
                res.add(m)
            }
            return res
        }

        private fun dispatchSelects(type: Type, site: VirtualCallSite, target: soot.SootMethod): Boolean {
            val targets = PTAUtils.dispatch(type, site)
            while (targets.hasNext()) {
                val m = targets.next()
                if (m != target) continue
                if (site.iie() is SpecialInvokeExpr) {
                    val calleeDeclType = m.declaringClass.type
                    if (!Scene.v().fastHierarchy.canStoreType(type, calleeDeclType)) continue
                }
                return true
            }
            return false
        }

        private fun kindOfTarget(n: Node): String = when {
            n is LocalVarNode && n.variable is soot.jimple.CastExpr -> "cast"
            n is LocalVarNode && n.variable is qilin.core.pag.Parm -> "param"
            n is LocalVarNode -> "local"
            else -> "global"
        }
    }

    /** A copy with another edge list over [vertexCount] vertices (graph rewrites such as [CutShortcut]; gates are shared). */
    internal fun withEdges(vertexCount: Int, edges: List<TEdge>): TypedGraph =
        TypedGraph(base, vertexCount, tagOf, tagTypes, tagEmptyArray, gates, gateKinds, edges, fieldCount).also { it.droppedAllocs = droppedAllocs }

    /** A copy with another edge list and an extended gate list (the container pattern adds filters of its own, M18). */
    internal fun withEdgesAndGates(vertexCount: Int, edges: List<TEdge>, gates: List<BitSet>, gateKinds: List<String>): TypedGraph =
        TypedGraph(base, vertexCount, tagOf, tagTypes, tagEmptyArray, gates, gateKinds, edges, fieldCount).also { it.droppedAllocs = droppedAllocs }

    /** Tags admitted by Spark's filter at a variable of declared type [t] (castNeverFails). */
    fun admitted(t: Type): BitSet {
        val b = BitSet(tagCount)
        for (i in 0 until tagCount) if (PTAUtils.castNeverFails(tagTypes[i], t)) b.set(i)
        return b
    }

    /** Is the vertex an allocation vertex of the base graph? */
    fun isAlloc(v: Int) = v < tagOf.size && tagOf[v] >= 0

    @Suppress("unused")
    private fun fieldOf(f: Int): Any = base.fields[f].let { if (it is SootField) it.signature else it.toString() }

    @Suppress("unused")
    private val objectType: Type get() = RefType.v("java.lang.Object")

    /**
     * Exact graph reduction (offline variable substitution, cf. Rountev & Chandra / Hardekopf & Lin):
     *  (1) every strongly connected component of plain ASSIGN edges collapses to one vertex (equal sets);
     *  (2) a variable whose only incoming value-flow edge is a plain ASSIGN `u -> v` gets pts(v) = pts(u)
     *      (plain means type(u) <: type(v): Spark's filter at v passes everything) and is merged into u.
     * Edge labels are kept (a label depends on the original endpoints' types and on the call site, and stays
     * exact because merged vertices have equal sets). Returns the reduced graph (same vertex ids; merged
     * vertices become isolated) and rep[v] = representative of v.
     */
    fun reduced(): Pair<TypedGraph, IntArray> {
        val n = vertexCount
        val rep = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (rep[r] != r) r = rep[r]; var y = x; while (rep[y] != r) { val nx = rep[y]; rep[y] = r; y = nx }; return r }
        fun isVar(v: Int) = !isAlloc(v)
        // (1) SCCs of plain ASSIGN edges (iterative Tarjan)
        val succ = Array(n) { IntArrayList() }
        for (e in edges) if (e.kind == L.ASSIGN && e.src != e.dst) succ[e.src].add(e.dst)
        val index = IntArray(n) { -1 }; val low = IntArray(n); val onStack = BooleanArray(n)
        val stack = IntArrayList(); var counter = 0
        val callV = IntArrayList(); val callI = IntArrayList()
        for (s0 in 0 until n) {
            if (index[s0] >= 0) continue
            callV.add(s0); callI.add(0); index[s0] = counter; low[s0] = counter; counter++; stack.add(s0); onStack[s0] = true
            while (callV.size > 0) {
                val v = callV.last(); val i = callI.last()
                if (i < succ[v].size) {
                    callI.setLast(i + 1)
                    val w = succ[v][i]
                    if (index[w] < 0) {
                        index[w] = counter; low[w] = counter; counter++; stack.add(w); onStack[w] = true
                        callV.add(w); callI.add(0)
                    } else if (onStack[w]) low[v] = minOf(low[v], index[w])
                } else {
                    callV.removeLast(); callI.removeLast()
                    if (callV.size > 0) { val p = callV.last(); low[p] = minOf(low[p], low[v]) }
                    if (low[v] == index[v]) {
                        while (true) { val w = stack.removeLast(); onStack[w] = false; rep[w] = v; if (w == v) break }
                    }
                }
            }
        }
        // (2) single plain-ASSIGN predecessor, on the SCC-condensed graph
        val inCount = IntArray(n); val inPlain = IntArray(n) { -1 }
        for (e in edges) {
            val src = find(e.src); val dst = find(e.dst)
            when (e.kind) {
                L.STORE -> {} // y -> b.f: no value flows into the base b
                L.ASSIGN -> if (src != dst) { inCount[dst]++; inPlain[dst] = src }
                else -> { inCount[dst] += 2 } // ALLOC, LOAD, GATE into dst: not substitutable
            }
        }
        for (v in 0 until n) if (find(v) == v && isVar(v) && inCount[v] == 1 && inPlain[v] >= 0) rep[v] = inPlain[v]
        for (v in 0 until n) find(v)
        // rewrite edges
        val out = LinkedHashSet<TEdge>()
        for (e in edges) {
            val s = rep[e.src]; val d = rep[e.dst]
            if ((e.kind == L.ASSIGN || e.kind == L.GATE) && s == d) continue // pts(s) ⊇ pts(s) ∩ P
            out.add(TEdge(e.kind, s, d, e.arg))
        }
        val g = TypedGraph(base, vertexCount, tagOf, tagTypes, tagEmptyArray, gates, gateKinds, out.toList(), fieldCount)
        g.droppedAllocs = droppedAllocs
        return g to rep
    }

    /** Minimal growable int list (avoids boxing in the reduction). */
    private class IntArrayList {
        private var a = IntArray(4); var size = 0; private set
        fun add(x: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = x }
        operator fun get(i: Int) = a[i]
        fun last() = a[size - 1]
        fun setLast(x: Int) { a[size - 1] = x }
        fun removeLast(): Int = a[--size]
    }
}
