package qilin.ucfs.benchmark

import qilin.ucfs.graph.ImportedGraph
import qilin.ucfs.graph.ConstraintGraph
import qilin.ucfs.graph.EdgeKind
import qilin.ucfs.ucfs.RSM_Provider
import qilin.ucfs.ucfs.RSM_Provider.PlainRsm

/**
 * Naive fixed-point Andersen analysis over a [ImportedGraph] with NO type filtering and NO dispatch
 * filtering: the semantics the CFL grammar [PlainRsm] is meant to compute.
 * Used only as a test oracle for the UCFS export/grammar (small graphs).
 *
 *   ALLOC o->x   : o in pts(x)
 *   ASSIGN y->x  : pts(y) ⊆ pts(x)
 *   STORE y->b.f : for o in pts(b): pts(y) ⊆ pts(o.f)
 *   LOAD b.f->x  : for o in pts(b): pts(o.f) ⊆ pts(x)
 */
object ReferenceAndersen {
    fun solve(pag: ConstraintGraph): Map<Int, Set<Int>> {
        val pts = HashMap<Int, MutableSet<Int>>()
        val fpts = HashMap<Long, MutableSet<Int>>()
        fun p(v: Int) = pts.getOrPut(v) { HashSet() }
        fun fp(o: Int, f: Int) = fpts.getOrPut(o.toLong() * 1_000_003L + f) { HashSet() }
        var changed = true
        while (changed) {
            changed = false
            for (e in pag.edges) {
                when (e.kind) {
                    EdgeKind.ALLOC -> changed = p(e.dst).add(e.src) or changed
                    EdgeKind.ASSIGN -> changed = p(e.dst).addAll(p(e.src)) or changed
                    EdgeKind.STORE -> for (o in p(e.dst).toList()) changed = fp(o, e.field).addAll(p(e.src)) or changed
                    EdgeKind.LOAD -> for (o in p(e.src).toList()) changed = p(e.dst).addAll(fp(o, e.field)) or changed
                }
            }
        }
        return pts.filterValues { it.isNotEmpty() }.filterKeys { !pag.isAlloc[it] }
    }
}
