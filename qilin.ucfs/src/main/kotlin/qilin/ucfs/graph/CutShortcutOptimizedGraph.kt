package qilin.ucfs.graph

import qilin.core.PTA
import qilin.core.pag.LocalVarNode
import qilin.core.pag.Parm
import qilin.ucfs.graph.TypedGraph.L
import qilin.ucfs.graph.TypedGraph.TEdge
import soot.RefLikeType
import soot.SootMethod
import soot.jimple.AssignStmt
import soot.jimple.InstanceInvokeExpr
import soot.jimple.NullConstant

/**
 * Cut-Shortcut (Ma et al., PLDI'23) as a static rewrite of the typed constraint graph (M14, D41).
 *
 * Context-insensitive analysis merges, inside a method, the flows of its different call sites. Cut-Shortcut cuts the
 * edges that carry the merged flows and adds "shortcut" edges at every call site. In the paper the shortcuts depend on
 * points-to sets (`o.f` for `o ∈ pt(a)`) and are built on the fly; here a shortcut is an ordinary edge at the call site
 * (store `a.f = b`, load `z = a.f`, assignment `a -> z`) and the solver computes `pt(a)` itself. Each shortcut repeats the
 * filters (gates) of the call edges it replaces through shadow vertices, so every shortcut flow is part of a flow of the
 * original graph and the result is a subset of Spark.
 *
 *  - FIELD, store (§3.2.1): `x.f = y` where x and y derive from parameters px, py of m. The store is cut; at each call site
 *    of m the store `ay~.f`... `ay~ -> ax~.f` is added (a~ = the argument through the filters of the call edge and of the
 *    derivation chain).
 *  - FIELD, load (§3.2.2) and LOCAL (§3.4): every value flowing into the return vertex of m comes, through assignments
 *    and gates inside m, either from a parameter (LOCAL) or from a load `r = b.f` with b derived from a parameter (FIELD).
 *    All return edges of m are cut; at each call site `z = m(...)` the corresponding assignments and loads are added.
 *  - Nested calls (§3.2.3): a shortcut is an ordinary edge of the caller and is checked again (worklist to a fixpoint).
 *
 * A vertex "derives" from a parameter if it is reached from it by a chain of ASSIGN/GATE edges inside the method in which
 * every vertex has exactly one incoming value-flow edge (the parameter is not redefined). A parameter qualifies only if all
 * its incoming edges are call edges ([callOnlyParams]). Methods in a cycle of the call graph are left alone.
 */
object CutShortcutOptimizedGraph {
    enum class Pattern { FIELD, LOCAL, CONTAINER }

    class Result(val graph: TypedGraph, val stats: Map<String, Int>)

    fun parse(spec: String): Set<Pattern> = spec.split(",").filter { it.isNotBlank() }.map { Pattern.valueOf(it.trim().uppercase()) }.toSet()

    /** Parameter vertices (this, formal parameters) whose every incoming edge is a call edge of the call graph. */
    fun callOnlyParams(pag: ImportedGraph): Set<Int> {
        val bad = HashSet<Int>()
        for (e in pag.edges) if (e.origin != EdgeOrigin.CALL_ARG && e.origin != EdgeOrigin.RECV_THIS) bad.add(e.dst)
        val res = HashSet<Int>()
        for ((v, n) in pag.nodes.withIndex()) {
            val p = (n as? LocalVarNode)?.variable as? Parm ?: continue
            if (p.isReturn || p.isThrowRet) continue
            if (v !in bad) res.add(v)
        }
        return res
    }

    /** One call graph edge: the caller, the argument vertices by parameter index (-1: none), receiver and destination. */
    private class Site(val caller: SootMethod, val args: IntArray, val recv: Int, val dest: Int)

    /** A value flowing into a return vertex: from parameter [param] (LOCAL) or from `base.field` with base from [param] (FIELD). */
    private class Leaf(val param: Int, val paramGates: List<Int>, val field: Int, val afterGates: List<Int>)

    private const val MAX_PATHS = 64
    private const val MAX_CHAIN = 64

    fun apply(pta: PTA, pag: ImportedGraph, tg: TypedGraph, patterns: Set<Pattern>): Result {
        val stats = LinkedHashMap<String, Int>()
        fun count(k: String, d: Int = 1) { stats[k] = (stats[k] ?: 0) + d }
        val trace = System.getenv("CS_TRACE") != null // debugging: print every decision
        // debugging (delta search for a lost pair): methods whose signature matches CS_SKIP are not rewritten
        val skip = System.getenv("CS_SKIP")?.let { Regex(it) }
        fun skipped(m: SootMethod) = skip != null && skip.containsMatchIn(m.signature)
        if (patterns.isEmpty()) return Result(tg, stats)
        // container access pattern first (M18): it cuts return edges of exit call sites; field/local then work on the result
        @Suppress("NAME_SHADOWING")
        val tg = if (Pattern.CONTAINER in patterns) ContainerOptimizedGraph.apply(pta, pag, tg, stats) else tg
        val field = Pattern.FIELD in patterns
        val local = Pattern.LOCAL in patterns
        val n0 = pag.nodes.size
        var nv = tg.vertexCount

        // value-flow edges indexed by target and source; stores separately (a store adds no value to its base)
        val edges = LinkedHashSet(tg.edges)
        val inc = HashMap<Int, MutableSet<TEdge>>()
        val out = HashMap<Int, MutableSet<TEdge>>()
        val stores = LinkedHashSet<TEdge>()
        fun index(e: TEdge) {
            if (e.kind == L.STORE) stores.add(e) else inc.getOrPut(e.dst) { LinkedHashSet() }.add(e)
            out.getOrPut(e.src) { LinkedHashSet() }.add(e)
        }
        for (e in edges) index(e)
        fun add(e: TEdge): Boolean { if (!edges.add(e)) return false; index(e); count("shortcutEdges"); return true }
        fun remove(e: TEdge) {
            edges.remove(e); out[e.src]?.remove(e)
            if (e.kind == L.STORE) stores.remove(e) else inc[e.dst]?.remove(e)
        }

        // method of a vertex: base vertices from Qilin, TypedGraph's load shadows from their gate target, ours when created
        val methodOf = HashMap<Int, SootMethod>()
        fun method(v: Int): SootMethod? {
            if (v < n0) return (pag.nodes[v] as? LocalVarNode)?.method
            methodOf[v]?.let { return it }
            val g = out[v]?.firstOrNull { it.kind == L.GATE } ?: return null
            return method(g.dst)?.also { methodOf[v] = it }
        }
        fun parm(v: Int): Parm? = if (v < n0) (pag.nodes[v] as? LocalVarNode)?.variable as? Parm else null
        val callOnly = callOnlyParams(pag)

        // call sites per target, from the same call graph edges as ImportedGraph's interprocedural edges
        val sites = HashMap<SootMethod, MutableList<Site>>()
        val callees = HashMap<SootMethod, MutableSet<SootMethod>>()
        for (e in pta.callGraph) {
            val s = e.srcStmt() ?: continue
            if (!s.containsInvokeExpr()) continue
            val ie = s.invokeExpr
            val tgt = e.tgt()
            val srcnf = pta.pag.getMethodPAG(e.src()).nodeFactory()
            val args = IntArray(tgt.parameterCount) { -1 }
            for (i in 0 until minOf(ie.argCount, tgt.parameterCount)) {
                val a = ie.getArg(i)
                if (a.type !is RefLikeType || a is NullConstant || tgt.getParameterType(i) !is RefLikeType) continue
                args[i] = pag.idOf(srcnf.getNode(a))
            }
            val recv = if (ie is InstanceInvokeExpr) pag.idOf(srcnf.getNode(ie.base)) else -1
            val dest = if (s is AssignStmt && s.leftOp.type is RefLikeType && tgt.returnType is RefLikeType) pag.idOf(srcnf.getNode(s.leftOp)) else -1
            sites.getOrPut(tgt) { ArrayList() }.add(Site(e.src(), args, recv, dest))
            callees.getOrPut(e.src()) { HashSet() }.add(tgt)
        }
        val recursive = recursiveMethods(callees)

        fun argOf(site: Site, p: Parm): Int = if (p.isThis) site.recv else if (p.index in site.args.indices) site.args[p.index] else -1
        fun label(src: Int, dst: Int): TEdge? = out[src]?.firstOrNull { it.dst == dst && (it.kind == L.ASSIGN || it.kind == L.GATE) }
        fun gatesOf(e: TEdge): List<Int> = if (e.kind == L.GATE) listOf(e.arg) else emptyList()

        /** (parameter vertex, gates from it to v) if v derives from a qualifying parameter of m. */
        fun derive(v: Int, m: SootMethod): Pair<Int, List<Int>>? {
            var cur = v
            val gates = ArrayList<Int>()
            repeat(MAX_CHAIN) {
                if (method(cur) != m) return null
                val p = parm(cur)
                if (p != null) return if (!p.isReturn && !p.isThrowRet && cur in callOnly) cur to gates.reversed() else null
                val ins = inc[cur]?.filter { it.src != cur } ?: return null // a self-loop adds nothing to pts(cur)
                if (ins.size != 1) return null
                val e = ins.first()
                when (e.kind) { L.ASSIGN -> {}; L.GATE -> gates.add(e.arg); else -> return null }
                cur = e.src
            }
            return null
        }

        /** Leaves of the flows into the return vertex [ret] of m, or null if some flow is not covered by the patterns. */
        fun leavesOf(ret: Int, m: SootMethod): List<Leaf>? {
            val leaves = ArrayList<Leaf>()
            val onPath = HashSet<Int>()
            fun walk(u: Int, after: List<Int>): Boolean {
                if (leaves.size > MAX_PATHS || method(u) != m || !onPath.add(u)) {
                    if (trace) println("CS   walk stop at $u ${if (u < n0) describe(pag.nodes[u]) else "shadow"} (method ${method(u)?.name}, onPath ${u in onPath})")
                    return false
                }
                try {
                    val p = parm(u)
                    if (p != null && !p.isReturn) {
                        if (!local || p.isThrowRet || u !in callOnly) return false
                        leaves.add(Leaf(u, emptyList(), -1, after)); return true
                    }
                    for (e in inc[u] ?: emptySet()) {
                        if (e.src == u && (e.kind == L.ASSIGN || e.kind == L.GATE)) continue // `a = id(a)`: pts(a) ⊇ pts(a)
                        val ok = when (e.kind) {
                            L.ASSIGN -> walk(e.src, after)
                            L.GATE -> walk(e.src, listOf(e.arg) + after)
                            L.LOAD -> field && derive(e.src, m)?.let { (pb, gb) -> leaves.add(Leaf(pb, gb, e.arg, after)) } == true
                            else -> false
                        }
                        if (!ok) { if (trace) println("CS   edge $e into $u rejected"); return false }
                    }
                    return true
                } finally { onPath.remove(u) }
            }
            return if (walk(ret, emptyList()) && leaves.isNotEmpty() && leaves.size <= MAX_PATHS) leaves else null
        }

        val shadows = HashMap<Pair<Int, Int>, Int>()
        /** The vertex `src` filtered by [gates] in order (shadow vertices of [caller]); the last step ends at [dst] if given. */
        fun path(src: Int, gates: List<Int>, caller: SootMethod, dst: Int = -1): Int {
            if (gates.isEmpty()) { if (dst >= 0) { if (src != dst) add(TEdge(L.ASSIGN, src, dst, -1)); return dst }; return src }
            var cur = src
            for ((i, g) in gates.withIndex()) {
                val next = if (i == gates.lastIndex && dst >= 0) dst else shadows.getOrPut(cur to g) { nv++.also { methodOf[it] = caller; count("shadowVertices") } }
                add(TEdge(L.GATE, cur, next, g))
                cur = next
            }
            return cur
        }
        val allocShadows = HashMap<Pair<Int, SootMethod>, Int>()
        /** The argument of [site] for [param], filtered as on the call edge and then by [gates]; null if nothing flows. */
        fun argPath(site: Site, param: Int, gates: List<Int>): Int? {
            val a = argOf(site, parm(param)!!)
            if (a < 0) return null
            val l = label(a, param)
            if (l != null) return path(a, gatesOf(l) + gates, site.caller)
            // a constant argument (string/class constant) is an allocation vertex: `o -> param` is an ALLOC edge
            val al = out[a]?.firstOrNull { it.dst == param && it.kind == L.ALLOC } ?: return null // no call edge: nothing flows
            val s = allocShadows.getOrPut(a to site.caller) {
                nv++.also { methodOf[it] = site.caller; count("shadowVertices"); add(TEdge(L.ALLOC, a, it, al.arg)) }
            }
            return path(s, gates, site.caller)
        }

        // worklist: stores and return vertices; a rewrite re-queues the caller's new stores and its return vertex
        val storeWork = ArrayDeque<TEdge>(if (field) stores.toList() else emptyList())
        val retWork = ArrayDeque<SootMethod>(sites.keys)
        val retOf = HashMap<SootMethod, Int>()
        fun retVertex(m: SootMethod): Int = retOf.getOrPut(m) {
            if (m.returnType !is RefLikeType) -1 else pag.idOf(pta.pag.getMethodPAG(m).nodeFactory().caseRet())
        }
        while (storeWork.isNotEmpty() || retWork.isNotEmpty()) {
            if (storeWork.isNotEmpty()) {
                val st = storeWork.removeFirst()
                if (st !in stores) continue
                val m = method(st.dst) ?: continue
                if (method(st.src) != m) continue
                if (m in recursive) { count("rejectedRecursive"); continue }
                if (skipped(m)) { count("skipped"); continue }
                val dx = derive(st.dst, m); val dy = derive(st.src, m)
                if (trace) println("CS store ${m.signature} f${st.arg}: base ${dx != null}, value ${dy != null}")
                val (px, gx) = dx ?: continue
                val (py, gy) = dy ?: continue
                val ss = sites[m] ?: continue
                remove(st); count("storesCut")
                for (site in ss) {
                    val base = argPath(site, px, gx) ?: continue
                    val value = argPath(site, py, gy) ?: continue
                    val ne = TEdge(L.STORE, value, base, st.arg)
                    if (add(ne)) storeWork.addLast(ne)
                }
                continue
            }
            val m = retWork.removeFirst()
            val ret = retVertex(m)
            if (ret < 0) continue
            val cuts = out[ret]?.filter { it.kind == L.ASSIGN || it.kind == L.GATE } ?: continue
            if (cuts.isEmpty()) continue
            if (m in recursive) { count("rejectedRecursive"); continue }
            if (skipped(m)) { count("skipped"); continue }
            val leaves = leavesOf(ret, m)
            if (trace) println("CS ret ${m.signature}: ${leaves?.size ?: "not covered"} leaves, ${cuts.size} return edges")
            if (leaves == null) { count("returnsNotCovered"); continue }
            val ss = sites[m] ?: continue
            for (e in cuts) remove(e)
            count(if (leaves.any { it.field >= 0 }) "returnsCutField" else "returnsCutLocal")
            for (site in ss) {
                if (site.dest < 0) continue
                val l = cuts.firstOrNull { it.dst == site.dest } ?: continue
                val tail = gatesOf(l)
                for (leaf in leaves) {
                    val after = leaf.afterGates + tail
                    if (leaf.field < 0) {
                        val a = argPath(site, leaf.param, emptyList()) ?: continue
                        path(a, after, site.caller, site.dest)
                    } else {
                        val base = argPath(site, leaf.param, leaf.paramGates) ?: continue
                        val t = if (after.isEmpty()) site.dest else nv++.also { methodOf[it] = site.caller; count("shadowVertices") }
                        add(TEdge(L.LOAD, base, t, leaf.field))
                        if (after.isNotEmpty()) path(t, after, site.caller, site.dest)
                    }
                }
                retWork.addLast(site.caller)
            }
        }
        stats["vertices"] = nv; stats["edges"] = edges.size
        return Result(tg.withEdges(nv, edges.toList()), stats)
    }

    /** Methods in a cycle of the call graph (including self-calls), by Tarjan's algorithm (iterative). */
    private fun recursiveMethods(callees: Map<SootMethod, Set<SootMethod>>): Set<SootMethod> {
        val index = HashMap<SootMethod, Int>(); val low = HashMap<SootMethod, Int>()
        val stack = ArrayList<SootMethod>(); val onStack = HashSet<SootMethod>()
        val res = HashSet<SootMethod>()
        var counter = 0
        for (s0 in callees.keys) {
            if (s0 in index) continue
            val work = ArrayList<Pair<SootMethod, Iterator<SootMethod>>>()
            fun visit(v: SootMethod) {
                index[v] = counter; low[v] = counter; counter++; stack.add(v); onStack.add(v)
                work.add(v to (callees[v] ?: emptySet()).iterator())
            }
            visit(s0)
            while (work.isNotEmpty()) {
                val (v, it) = work.last()
                if (it.hasNext()) {
                    val w = it.next()
                    if (w !in index) visit(w) else if (w in onStack) low[v] = minOf(low.getValue(v), index.getValue(w))
                } else {
                    work.removeAt(work.size - 1)
                    if (work.isNotEmpty()) { val p = work.last().first; low[p] = minOf(low.getValue(p), low.getValue(v)) }
                    if (low[v] == index[v]) {
                        val comp = ArrayList<SootMethod>()
                        while (true) { val w = stack.removeAt(stack.size - 1); onStack.remove(w); comp.add(w); if (w == v) break }
                        if (comp.size > 1 || v in (callees[v] ?: emptySet())) res.addAll(comp)
                    }
                }
            }
        }
        return res
    }
}
