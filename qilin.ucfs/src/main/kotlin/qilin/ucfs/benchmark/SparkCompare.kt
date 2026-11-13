package qilin.ucfs.benchmark

import qilin.ucfs.graph.ImportedGraph
import qilin.ucfs.graph.TypedGraph

import qilin.core.PTA
import qilin.core.pag.AllocNode
import qilin.core.pag.ContextAllocNode
import qilin.core.pag.VarNode
import qilin.core.pag.VirtualCallSite
import soot.jimple.InstanceInvokeExpr
import soot.jimple.Stmt
import soot.jimple.toolkits.callgraph.Edge

/**
 * Oracle: compares CI points-to sets of a solver with Spark's, variable by variable, on the same exported
 * graph (M4 D4.2). Spark's sets are read through Qilin's public API (`reachingObjects`), never from our graph.
 */
object SparkCompare {
    /** Spark's CI points-to sets of all variables of [pag] (vertex ids). */
    fun sparkSets(pta: PTA, pag: ImportedGraph): Map<Int, Set<Int>> =
        pag.graph.varIds().associateWith { v ->
            pta.reachingObjects(pag.nodes[v]).toCIPointsToSet().toCollection().map { pag.idOf(it) }.toSet()
        }

    fun rootId(pta: PTA, pag: ImportedGraph): Int =
        pag.idOf(pta.rootNode.let { if (it is ContextAllocNode) it.base() else it })

    class VarDiff(val v: Int, val sparkOnly: Set<Int>, val solverOnly: Set<Int>)

    class Result(
        val vars: Int,
        val equal: Int,
        /** solver ⊋ Spark */
        val superset: Int,
        /** solver ⊊ Spark */
        val subset: Int,
        val incomparable: Int,
        val sparkPairs: Long,
        val solverPairs: Long,
        val diffs: List<VarDiff>,
    ) {
        val differ get() = superset + subset + incomparable
        fun stats(prefix: String) = linkedMapOf<String, Any>(
            "${prefix}vars" to vars, "${prefix}varsEqual" to equal, "${prefix}varsSuperset" to superset,
            "${prefix}varsSubset" to subset, "${prefix}varsIncomparable" to incomparable,
            "${prefix}sparkPairs" to sparkPairs, "${prefix}solverPairs" to solverPairs,
            "${prefix}missingPairs" to diffs.sumOf { it.sparkOnly.size }.toLong(),
            "${prefix}extraPairs" to diffs.sumOf { it.solverOnly.size }.toLong(),
        )
    }

    /** [ignore]: vertices removed from the solver's sets before comparing (the fake ROOT object, D12). */
    fun compare(spark: Map<Int, Set<Int>>, solver: Map<Int, Set<Int>>, ignore: Set<Int> = emptySet()): Result {
        var eq = 0; var sup = 0; var sub = 0; var inc = 0
        var sp = 0L; var so = 0L
        val diffs = ArrayList<VarDiff>()
        for ((v, s) in spark) {
            val m = (solver[v] ?: emptySet()) - ignore
            sp += s.size; so += m.size
            if (m == s) { eq++; continue }
            val sparkOnly = s - m
            val solverOnly = m - s
            when {
                sparkOnly.isEmpty() -> sup++
                solverOnly.isEmpty() -> sub++
                else -> inc++
            }
            diffs.add(VarDiff(v, sparkOnly, solverOnly))
        }
        return Result(spark.size, eq, sup, sub, inc, sp, so, diffs)
    }

    class CallGraphResult(val sites: Int, val sparkEdges: Int, val derivedEdges: Int, val missing: Int, val extra: Int,
                          val sitesDiffer: Int, val sparkPoly: Int, val derivedPoly: Int)

    /**
     * Call graph derived from a solver's receiver sets: for every instance call site of Spark's CI call graph,
     * the targets are dispatch(type(o)) over the receiver's objects (the same dispatch Spark uses). Compared
     * with Spark's targets of that site, counting only virtual/interface/special edges (other kinds, e.g.
     * Thread.start -> run or <clinit>, do not depend on the receiver set the same way).
     */
    fun derivedCallGraph(pta: PTA, pag: ImportedGraph, pts: Map<Int, Set<Int>>): CallGraphResult {
        val bySite = LinkedHashMap<Stmt, MutableList<Edge>>()
        for (e in pta.callGraph) {
            if (e.srcStmt()?.invokeExpr !is InstanceInvokeExpr) continue
            if (e.kind() != soot.Kind.VIRTUAL && e.kind() != soot.Kind.INTERFACE && e.kind() != soot.Kind.SPECIAL) continue
            bySite.getOrPut(e.srcStmt()) { ArrayList() }.add(e)
        }
        var sparkEdges = 0; var derivedEdges = 0; var missing = 0; var extra = 0; var differ = 0
        var sparkPoly = 0; var derivedPoly = 0
        for ((stmt, edges) in bySite) {
            val e0 = edges.first()
            val ie = stmt.invokeExpr as InstanceInvokeExpr
            val recv = pta.pag.getMethodPAG(e0.src()).nodeFactory().getNode(ie.base) as VarNode
            val site = VirtualCallSite(recv, stmt, pta.parameterize(e0.src(), pta.emptyContext()), ie,
                ie.methodRef.subSignature, Edge.ieToKind(ie))
            val sparkT = edges.map { it.tgt() }.toSet()
            val derived = HashSet<soot.SootMethod>()
            for (o in pts[pag.idOf(recv)] ?: emptySet()) {
                val a = pag.nodes[o] as? AllocNode ?: continue
                derived.addAll(TypedGraph.dispatchTargets(a.type, site))
            }
            sparkEdges += sparkT.size; derivedEdges += derived.size
            missing += sparkT.count { it !in derived }; extra += derived.count { it !in sparkT }
            if (sparkT != derived) differ++
            if (sparkT.size > 1) sparkPoly++
            if (derived.size > 1) derivedPoly++
        }
        return CallGraphResult(bySite.size, sparkEdges, derivedEdges, missing, extra, differ, sparkPoly, derivedPoly)
    }

    /**
     * Classifies every pair (v, o) that [plain] has and Spark has not by the single Spark filters whose
     * removal makes it appear: class = { F : (v, o) ∈ typed-without-F }. An empty class means that the
     * pair needs at least two filters switched off ("combination").
     */
    fun classify(spark: Map<Int, Set<Int>>, plain: Map<Int, Set<Int>>, ablated: Map<TypedGraph.Filter, Map<Int, Set<Int>>>,
                 ignore: Set<Int>): Map<String, MutableList<Pair<Int, Int>>> {
        val classes = LinkedHashMap<String, MutableList<Pair<Int, Int>>>()
        for ((v, s) in spark) {
            for (o in (plain[v] ?: emptySet()) - ignore - s) {
                val cls = ablated.filter { (_, r) -> r[v]?.contains(o) == true }.keys.sorted().joinToString("+").ifEmpty { "combination" }
                classes.getOrPut(cls) { ArrayList() }.add(v to o)
            }
        }
        return classes
    }
}
