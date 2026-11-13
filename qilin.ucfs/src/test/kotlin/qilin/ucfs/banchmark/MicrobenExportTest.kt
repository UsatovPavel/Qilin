package qilin.ucfs.banchmark

import qilin.ucfs.graph.CiPag
import qilin.ucfs.QilinSession
import qilin.ucfs.ucfs.UcfsPointsTo
import qilin.ucfs.graph.describe

import org.junit.BeforeClass
import org.junit.Test
import qilin.core.pag.AllocNode
import qilin.core.pag.LocalVarNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M3 acceptance: Spark -> CI constraint graph -> UCFS (points-to grammar) on Qilin micro-benchmarks. */
class MicrobenExportTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp() // Soot/Qilin are JVM-wide singletons: same options as CorePagTest
    }

    private fun check(mainClass: String): Pair<CiPag, Map<Int, Set<Int>>> {
        val pta = QilinSession.runSpark(mainClass)
        val pag = CiPag.build(pta)
        val ucfs = UcfsPointsTo.solve(pag.graph).pointsTo
        assertEquals(ReferenceAndersen.solve(pag.graph), ucfs, "$mainClass: UCFS = reference Andersen (no type filter) on the exported graph")
        var sparkPairs = 0
        for (v in pag.graph.varIds()) {
            val spark = pta.reachingObjects(pag.nodes[v]).toCIPointsToSet().toCollection().map { pag.idOf(it) }.toSet()
            sparkPairs += spark.size
            assertTrue((ucfs[v] ?: emptySet()).containsAll(spark), "$mainClass: Spark ⊄ UCFS at ${describe(pag.nodes[v])}: spark=$spark ucfs=${ucfs[v]}")
        }
        assertTrue(sparkPairs > 0, "$mainClass: Spark has points-to facts")
        return pag to ucfs
    }

    @Test
    fun fieldSensitivity1() {
        val (pag, ucfs) = check(CorePagTest.FS1)
        val main = "<${CorePagTest.FS1}: void main(java.lang.String[])>"
        fun local(name: String) = pag.nodes.indices.single { val n = pag.nodes[it]; n is LocalVarNode && n.method.signature == main && n.variable.toString() == name }
        // Jimple reuses the local o1 (`o1 = new Object; ...; o1 = a.f; ...; o1 = a.g`): it may hold both objects
        val o2 = ucfs[local("o2")]!!.single()
        val o1 = ucfs[local("o1")]!!
        assertTrue(pag.nodes[o2] is AllocNode)
        assertEquals(2, o1.size, "o1 holds the first new Object and, via a.f / a.g, also o2's object")
        assertTrue(o2 in o1)
        assertEquals(1, ucfs[local("a")]!!.size, "a = new A")
    }

    @Test
    fun spark_subset_of_ucfs_on_microbenchmarks() {
        for (mc in listOf(
            "qilin.microben.core.field.FieldSensitivity2",
            "qilin.microben.core.assign.Receiver2This",
            "qilin.microben.core.call.VirtualCall1",
            "qilin.microben.core.array.ArrayCopy",
        )) check(mc)
    }
}
