package qilin.ucfs

import qilin.core.PTA
import qilin.util.PTAUtils
import soot.Local
import soot.RefLikeType
import soot.jimple.AssignStmt
import soot.jimple.CastExpr
import soot.jimple.InstanceInvokeExpr
import soot.jimple.Stmt

/**
 * Query variables for demand-driven runs (M13, advisor's point 6: "UCFS was designed as an engine for analysis from a
 * concrete point"): CI-graph vertex ids of the variables a client actually asks about.
 *  - casts:     operands y of reference casts `x = (T) y` in reachable methods (the may-fail-cast client of Qilin);
 *  - receivers: bases of instance calls (the call-graph client);
 *  - sample:N:  N variables chosen with a fixed seed (a generic "a few queries" client).
 */
object QuerySets {
    fun of(spec: String, pta: PTA, pag: CiPag): List<Int> {
        fun localsWhere(pick: (Stmt) -> Local?): List<Int> {
            val out = LinkedHashSet<Int>()
            for (momc in pta.cgb.reachableMethods) {
                for (u in PTAUtils.getMethodBody(momc.method()).units) {
                    val l = pick(u as Stmt) ?: continue
                    val n = pta.pag.findLocalVarNode(l) ?: continue
                    val id = pag.idOf(n)
                    if (id >= 0) out.add(id)
                }
            }
            return out.toList()
        }
        return when {
            spec == "casts" -> localsWhere { st ->
                if (st is AssignStmt && st.rightOp is CastExpr && st.leftOp.type is RefLikeType) (st.rightOp as CastExpr).op as? Local else null
            }
            spec == "receivers" -> localsWhere { st ->
                if (st.containsInvokeExpr()) (st.invokeExpr as? InstanceInvokeExpr)?.base as? Local else null
            }
            // var:<substring>: the variables whose description contains the substring (debugging a single pair)
            spec.startsWith("var:") -> (0 until pag.nodes.size).filter { !pag.graph.isAlloc[it] && describe(pag.nodes[it]).contains(spec.substringAfter(":")) }
            spec.startsWith("sample:") -> {
                val k = spec.substringAfter(":").toInt()
                pag.graph.varIds().shuffled(java.util.Random(42)).take(k).sorted()
            }
            else -> error("unknown --query=$spec (casts, receivers, sample:N)")
        }
    }
}
