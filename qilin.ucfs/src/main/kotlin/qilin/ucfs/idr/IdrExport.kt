package qilin.ucfs.idr

import qilin.ucfs.graph.ImportedGraph
import qilin.ucfs.graph.EdgeKind
import qilin.ucfs.graph.EdgeOrigin
import qilin.ucfs.graph.describe

import qilin.core.PTA
import qilin.core.pag.AllocNode
import qilin.util.PTAUtils
import soot.RefLikeType
import soot.Unit as SootUnit
import soot.jimple.AssignStmt
import soot.jimple.InstanceInvokeExpr
import soot.jimple.NullConstant
import java.io.File

/**
 * Export of the CI constraint graph ([ImportedGraph]) to the input of cfl-idr-with-gll (interleaved Dyck reachability),
 * in value-flow orientation (an edge u -> v means "the value of u flows into v"):
 *
 *   ALLOC o -> x, ASSIGN y -> x inside a method (incl. ROOT, FINALIZER)  : `normal`
 *   argument / receiver passing at call site c  (arg -> parm, recv -> this) : `op--c`
 *   return at call site c                        (ret -> dest)              : `cp--c`
 *   store b.f = y                                (y -> b)                   : `ob--f`
 *   load  x = b.f                                (b -> x)                   : `cb--f`
 *
 * Vertex ids 0 until pag.nodes.size are the [ImportedGraph] vertex ids; field ids are [ImportedGraph] field ids; call-site ids are dense
 * over the call statements of Spark's final CI call graph (one id per statement, shared by all its targets).
 * Interprocedural edges are recomputed from the call graph exactly like [ImportedGraph] does, but per call site (ImportedGraph merges
 * equal edges of different call sites, which is right for CI points-to but loses the parenthesis).
 *
 * Encodings ([Encoding], M7):
 *  - VF: the above (M6);
 *  - VFPB: VF + partially balanced calls: a source s_o per object (`s_o -normal-> o`, self-loops `op--c` for every call
 *    site c that returns) and a sink t_x per variable (`x -normal-> t_x`, self-loops `cp--c` for every call site c that
 *    passes values in). A fully balanced s_o ~> t_x path is exactly a path o ~> x of the form `cp* balanced op*` in α;
 *  - HEAPPB: VFPB, but store/load go through object-field vertices h(o,f) as in SVF value-flow graphs:
 *    `y -ob--f-> h(o,f)` for each o ∈ pts_Spark(b) of a store b.f = y and `h(o,f) -cb--f-> x` for each o ∈ pts_Spark(b')
 *    of a load x = b'.f (empty arrays excluded, as Spark does). Fields are resolved by the CI pre-analysis;
 *  - HEAPCIPB: HEAPPB with a context-insensitive heap, as in k-CFA without heap contexts: every store goes through a
 *    fresh vertex `y -normal-> y'` and every load through `x' -normal-> x`, both with self-loops `op--c` and `cp--c` for
 *    all call sites, so the call stack is unconstrained at a heap access (a value stored in one context can be loaded in
 *    another). The loops sit outside the field brackets, so the union-Dyck under-approximation stays well nested (M7, D25).
 */
class IdrExport private constructor(
    val edges: List<IdrEdge>,
    val callSites: Int,
    val pag: ImportedGraph,
    val encoding: Encoding,
    /** extra vertices (ids from pag.nodes.size): kind and base ImportedGraph vertex (object for heap/src, variable for sink) */
    val extraKind: List<String>,
    val extraBase: IntArray,
    /** call-site id -> call statement (for checks and reports) */
    val siteStmts: List<SootUnit>,
) {
    enum class Encoding { VF, VFPB, HEAPPB, HEAPCIPB }

    data class IdrEdge(val src: Int, val dst: Int, val label: String)

    val vertexCount get() = pag.nodes.size + extraKind.size

    fun count(prefix: String) = edges.count { it.label.startsWith(prefix) }

    /**
     * Maps a reachability pair (u, v) of cfl-idr to a points-to fact (variable, object) of the CI graph, or null:
     * VF: u object, v variable; VFPB/HEAPPB: u = s_o, v = t_x.
     */
    fun pointsToFact(u: Int, v: Int): Pair<Int, Int>? {
        val n = pag.nodes.size
        return when (encoding) {
            Encoding.VF -> if (u < n && v < n && pag.graph.isAlloc[u] && !pag.graph.isAlloc[v]) v to u else null
            else -> if (u >= n && v >= n && extraKind[u - n] == "src" && extraKind[v - n] == "sink") extraBase[v - n] to extraBase[u - n] else null
        }
    }

    /** Writes `digraph` text accepted by UCFS `DotParser` (and the cfl-idr CLI) and `<name>.map.tsv` (id -> node). */
    fun writeDot(f: File) {
        f.parentFile?.mkdirs()
        f.bufferedWriter().use { w ->
            w.write("digraph G {\n")
            for (e in edges) w.write("${e.src}->${e.dst}[label=\"${e.label}\"];\n")
            w.write("}\n")
        }
        File(f.path.removeSuffix(".dot") + ".map.tsv").bufferedWriter().use { w ->
            w.write("id\tkind\tnode\n")
            for ((i, n) in pag.nodes.withIndex()) w.write("$i\t${if (pag.graph.isAlloc[i]) "alloc" else "var"}\t${clean(describe(n))}\n")
            for ((j, k) in extraKind.withIndex()) w.write("${pag.nodes.size + j}\t$k\t${extraBase[j]}\n")
        }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    companion object {
        fun build(pta: PTA, pag: ImportedGraph, encoding: Encoding = Encoding.VF): IdrExport {
            val out = LinkedHashSet<IdrEdge>()
            val extraKind = ArrayList<String>()
            val extraBase = ArrayList<Int>()
            fun extra(kind: String, base: Int): Int { extraKind.add(kind); extraBase.add(base); return pag.nodes.size + extraKind.size - 1 }
            val heap = HashMap<Pair<Int, Int>, Int>() // (object, field) -> vertex
            val ciHeap = ArrayList<Int>()              // HEAPCIPB: vertices around heap accesses with free call stack
            fun objectsOf(b: Int): List<Int> = pta.reachingObjects(pag.nodes[b]).toCIPointsToSet().toCollection()
                .filter { !PTAUtils.isEmptyArray(it) }.map { pag.idOf(it) }.filter { it >= 0 }
            for (e in pag.graph.edges) {
                when (e.kind) {
                    EdgeKind.ALLOC -> out.add(IdrEdge(e.src, e.dst, "normal"))
                    EdgeKind.ASSIGN -> if (e.origin == EdgeOrigin.INTRA || e.origin == EdgeOrigin.ROOT || e.origin == EdgeOrigin.FINALIZER)
                        out.add(IdrEdge(e.src, e.dst, "normal"))
                    EdgeKind.STORE -> when (encoding) {
                        Encoding.VF, Encoding.VFPB -> out.add(IdrEdge(e.src, e.dst, "ob--${e.field}")) // y -> b
                        Encoding.HEAPPB -> for (o in objectsOf(e.dst)) out.add(IdrEdge(e.src, heap.getOrPut(o to e.field) { extra("heap", o) }, "ob--${e.field}"))
                        Encoding.HEAPCIPB -> {
                            val y1 = extra("ciheap", e.src).also { ciHeap.add(it) }
                            out.add(IdrEdge(e.src, y1, "normal"))
                            for (o in objectsOf(e.dst)) out.add(IdrEdge(y1, heap.getOrPut(o to e.field) { extra("heap", o) }, "ob--${e.field}"))
                        }
                    }
                    EdgeKind.LOAD -> when (encoding) {
                        Encoding.VF, Encoding.VFPB -> out.add(IdrEdge(e.src, e.dst, "cb--${e.field}"))  // b -> x
                        Encoding.HEAPPB -> for (o in objectsOf(e.src)) out.add(IdrEdge(heap.getOrPut(o to e.field) { extra("heap", o) }, e.dst, "cb--${e.field}"))
                        Encoding.HEAPCIPB -> {
                            val x1 = extra("ciheap", e.dst).also { ciHeap.add(it) }
                            for (o in objectsOf(e.src)) out.add(IdrEdge(heap.getOrPut(o to e.field) { extra("heap", o) }, x1, "cb--${e.field}"))
                            out.add(IdrEdge(x1, e.dst, "normal"))
                        }
                    }
                }
            }
            val siteIds = LinkedHashMap<SootUnit, Int>()
            val qpag = pta.pag
            fun id(n: qilin.core.pag.Node?): Int = if (n == null) -1 else pag.idOf(n)
            for (e in pta.callGraph) {
                val s = e.srcStmt()
                val c = siteIds.getOrPut(s) { siteIds.size }
                val srcnf = qpag.getMethodPAG(e.src()).nodeFactory()
                val tgtmpag = qpag.getMethodPAG(e.tgt())
                val tgtnf = tgtmpag.nodeFactory()
                val tgt = tgtmpag.method
                val ie = s.invokeExpr
                for (i in 0 until ie.argCount) {
                    val arg = ie.getArg(i)
                    if (arg.type !is RefLikeType || arg is NullConstant) continue
                    if (tgt.getParameterType(i) !is RefLikeType) continue
                    val a = id(srcnf.getNode(arg)); val p = id(tgtnf.caseParm(i))
                    if (a >= 0 && p >= 0) out.add(IdrEdge(a, p, "op--$c"))
                }
                if (s is AssignStmt) {
                    val dest = s.leftOp
                    if (dest.type is RefLikeType && tgt.returnType is RefLikeType) {
                        val r = id(tgtnf.caseRet()); val d = id(srcnf.getNode(dest))
                        if (r >= 0 && d >= 0) out.add(IdrEdge(r, d, "cp--$c"))
                    }
                }
                if (ie is InstanceInvokeExpr) {
                    val r = id(srcnf.getNode(ie.base)); val t = id(tgtnf.caseThis())
                    if (r >= 0 && t >= 0) out.add(IdrEdge(r, t, "op--$c"))
                }
            }
            if (encoding != Encoding.VF) {
                val returning = out.filter { it.label.startsWith("cp--") }.map { it.label.substringAfter("--") }.toSortedSet(compareBy { it.toInt() })
                val entering = out.filter { it.label.startsWith("op--") }.map { it.label.substringAfter("--") }.toSortedSet(compareBy { it.toInt() })
                for (o in pag.graph.allocIds()) {
                    val s = extra("src", o)
                    out.add(IdrEdge(s, o, "normal"))
                    for (c in returning) out.add(IdrEdge(s, s, "op--$c"))
                }
                for (x in pag.graph.varIds()) {
                    val t = extra("sink", x)
                    out.add(IdrEdge(x, t, "normal"))
                    for (c in entering) out.add(IdrEdge(t, t, "cp--$c"))
                }
                // static fields are context-insensitive like the heap: the call stack is free there too (M15 A0; without it a
                // flow `argument -> static -> result at another call site` was lost)
                if (encoding == Encoding.HEAPCIPB) for (v in pag.nodes.indices) if (pag.nodes[v] is qilin.core.pag.GlobalVarNode) ciHeap.add(v)
                for (h in ciHeap) {
                    for (c in entering) out.add(IdrEdge(h, h, "cp--$c"))   // close calls entered before the heap access
                    for (c in returning) out.add(IdrEdge(h, h, "op--$c"))  // allow returns after it
                }
            }
            return IdrExport(out.toList(), siteIds.size, pag, encoding, extraKind, extraBase.toIntArray(), siteIds.keys.toList())
        }
    }
}
