package qilin.ucfs.results

import qilin.ucfs.graph.CiPag

import qilin.core.PTA
import qilin.core.pag.AllocNode
import qilin.core.pag.ContextVarNode
import qilin.core.pag.VarNode
import qilin.stat.SimplifiedEvaluator

/**
 * Import of externally computed CI points-to sets into a solved Qilin analysis: the points-to sets
 * of the PAG are discarded (`PAG.resetPointsToSet`, as `StagedPTA` does between stages) and refilled
 * from [pts] (vertex ids of [pag]); afterwards every Qilin client — `reachingObjects`, the evaluators,
 * dumps — sees the imported result. Only variable points-to sets are imported; field contents
 * (`o.f`) are left empty (the standard metrics do not read them).
 */
object PtsImport {
    fun inject(pta: PTA, pag: CiPag, pts: Map<Int, Set<Int>>) {
        val qpag = pta.pag
        qpag.resetPointsToSet()
        val ctx = pta.emptyContext()
        for ((v, objs) in pts) {
            val node = pag.nodes[v] as? VarNode ?: continue
            val cvn = pta.parameterize(node, ctx) as ContextVarNode
            val set = cvn.p2Set
            for (o in objs) {
                val alloc = pag.nodes[o] as AllocNode
                set.add(pta.parameterize(alloc, ctx).number)
            }
            set.flushNew()
        }
    }

    /** CI points-to sets currently held by Qilin for the variables of [pag]. */
    fun current(pta: PTA, pag: CiPag): Map<Int, Set<Int>> {
        val res = HashMap<Int, Set<Int>>()
        for (v in pag.graph.varIds()) {
            val s = pta.reachingObjects(pag.nodes[v]).toCIPointsToSet().toCollection().map { pag.idOf(it) }.toSet()
            if (s.isNotEmpty()) res[v] = s
        }
        return res
    }

    val METRICS = listOf(
        "#Reachable Method (CI):", "#Call Edge(CI):", "#Virtual Call Site(Polymorphic):",
        "#May Fail Cast (Total):", "#Avg Points-to Target without Native Var(CI):",
    )

    /** Runs Qilin's own SimplifiedEvaluator on the current state of [pta]; returns metric -> value. */
    fun qilinMetrics(pta: PTA): Map<String, String> {
        val ev = SimplifiedEvaluator(pta)
        ev.begin()
        ev.end()
        val report = ev.toString()
        val res = LinkedHashMap<String, String>()
        for (line in report.lines()) for (m in METRICS) if (line.trimStart().startsWith(m)) res[m] = line.substringAfter(m).trim()
        return res
    }
}
