package qilin.ucfs

import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M14 acceptance D14.1 (c): Qilin's own context-sensitivity microbenchmarks (`qilin.microben.context.*`, not written by us).
 * Under Cut-Shortcut every `mayAlias` holds (as under Spark), the result is a subset of Spark, and CFA1k0's `notAlias` holds
 * (local flow pattern). The number of `notAlias` that hold is printed (no threshold).
 */
class CutShortcutMicrobenTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp()

        const val ASSERT = "qilin.microben.utils.Assert"
        val PROGRAMS = listOf("CFA1k0", "CFA1k1", "CFA1k2", "CFA2k").map { "qilin.microben.context.cfa.$it" } +
            listOf("OBJ1k0", "OBJ1k1", "OBJ1k2", "OBJ1k3", "OBJ1k4", "OBJ1k5", "OBJ2k0", "OBJ2k1", "OBJ2k2").map { "qilin.microben.context.obj.$it" } +
            listOf("ArrayList0", "HashMap0", "HashSet0", "HashTable0", "LinkedList0", "PriorityQueue0", "Stack0", "TreeMap0", "TreeSet0", "Vector0")
                .map { "qilin.microben.context.collections.$it" } +
            listOf("Hyb0", "Hyb1", "Hyb2").map { "qilin.microben.context.hyb.$it" } +
            listOf("Type1k0", "Type2k0").map { "qilin.microben.context.type.$it" }
    }

    @Test
    fun contextMicrobenchmarksAreSound() {
        val report = ArrayList<String>()
        var notHeld = 0; var notTotal = 0
        for (mc in PROGRAMS) {
            val pta = QilinSession.runSpark(mc)
            val pag = CiPag.build(pta)
            val spark = SparkCompare.sparkSets(pta, pag)
            val cs = CutShortcut.apply(pta, pag, TypedGraph.build(pta, pag), CutShortcut.parse("field,local"))
            val t0 = System.nanoTime()
            val pts = TypedPointsTo.solve(cs.graph, reduce = true, reachOnly = true).pointsTo
            val root = SparkCompare.rootId(pta, pag)
            val notSubset = pag.graph.varIds().filter { v -> !(spark[v] ?: emptySet()).containsAll((pts[v] ?: emptySet()) - root) }
            assertEquals(emptyList(), notSubset.map { describe(pag.nodes[it]) }, "$mc: subset of Spark")
            val checks = AliasChecks.evaluate(pta, pag, pts, ASSERT)
            assertTrue(checks.isNotEmpty(), "$mc: has assertions")
            for (c in checks.filter { it.may }) assertTrue(c.holds, "$mc: mayAlias must hold: ${c.where}")
            val nots = checks.filter { !it.may }
            notTotal += nots.size; notHeld += nots.count { it.holds }
            report.add("$mc notAlias ${nots.count { it.holds }}/${nots.size} vertices=${pag.nodes.size} solveMs=${(System.nanoTime() - t0) / 1_000_000} cs=${cs.stats}")
            println(report.last()); System.out.flush()
            if (mc.endsWith("CFA1k0")) for (c in nots) assertTrue(c.holds, "$mc: notAlias (local flow pattern): ${c.where}")
        }
        println("CUTSHORTCUT-MICROBEN notAlias held $notHeld/$notTotal\n" + report.joinToString("\n"))
    }
}
