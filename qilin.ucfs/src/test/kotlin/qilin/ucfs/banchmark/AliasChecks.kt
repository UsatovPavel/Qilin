package qilin.ucfs.banchmark

import qilin.ucfs.graph.CiPag

import qilin.core.PTA
import qilin.util.PTAUtils
import soot.Local
import soot.jimple.StaticInvokeExpr
import soot.jimple.Stmt

/** `mayAlias`/`notAlias` assertions of a test program, evaluated on a points-to result (as Qilin's AssertionsParser). */
object AliasChecks {
    data class Check(val may: Boolean, val holds: Boolean, val where: String)

    fun evaluate(pta: PTA, pag: CiPag, pts: Map<Int, Set<Int>>, owner: String): List<Check> {
        val root = SparkCompare.rootId(pta, pag)
        val res = ArrayList<Check>()
        for (m in pta.nakedReachableMethods) {
            if (m.isNative || m.isPhantom) continue
            for (u in PTAUtils.getMethodBody(m).units) {
                val st = u as Stmt
                if (!st.containsInvokeExpr()) continue
                val ie = st.invokeExpr as? StaticInvokeExpr ?: continue
                val sig = ie.methodRef.signature
                val may = when (sig) {
                    "<$owner: void mayAlias(java.lang.Object,java.lang.Object)>" -> true
                    "<$owner: void notAlias(java.lang.Object,java.lang.Object)>" -> false
                    else -> continue
                }
                fun setOf(i: Int): Set<Int> {
                    val l = ie.getArg(i) as? Local ?: return emptySet()
                    val n = pta.pag.findLocalVarNode(l) ?: return emptySet()
                    val id = pag.idOf(n)
                    return if (id < 0) emptySet() else (pts[id] ?: emptySet()) - root
                }
                val inter = setOf(0).intersect(setOf(1)).isNotEmpty()
                res.add(Check(may, if (may) inter else !inter, "${m.signature}: $st (line ${st.javaSourceStartLineNumber})"))
            }
        }
        return res
    }
}
