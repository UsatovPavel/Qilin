package qilin.ucfs.benchmark

import qilin.ucfs.graph.ImportedGraph
import qilin.ucfs.results.PointsToSetsImport
import qilin.ucfs.QilinSession
import qilin.ucfs.graph.TypedGraph
import qilin.ucfs.ucfs.TypedPointsToShared
import qilin.ucfs.graph.describe

import org.junit.BeforeClass
import org.junit.Test
import qilin.core.PTA
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M4 acceptance (D4.1, D4.4).
 *
 * (1) UCFS-typed (typed constraint graph + typed points-to RSM) equals Spark for every variable of the
 *     exported CI graph. The only admitted difference is the documented one: the fake ROOT object, which
 *     Spark puts into `FakeMain.this` without registering it in the allocation numberer (PTA.java), so it is
 *     invisible through `reachingObjects`.
 * (2) Import: discarding Qilin's points-to sets and writing Spark's own CI sets back gives the same
 *     Qilin metrics as Spark; writing the UCFS-typed sets gives the same metrics as Spark.
 */
class TypedSparkTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp() // Soot/Qilin are JVM-wide singletons: same options as CorePagTest

        val PROGRAMS = listOf(
            "qilin.microben.core.field.FieldSensitivity1",
            "qilin.microben.core.field.FieldSensitivity2",
            "qilin.microben.core.assign.CastSucc",
            "qilin.microben.core.assign.Receiver2This",
            "qilin.microben.core.call.VirtualCall0",
            "qilin.microben.core.call.VirtualCall1",
            "qilin.microben.core.array.ArrayElemTypeFiltering",
            "qilin.microben.core.array.ArrayCopy",
        )
    }

    private fun sparkSets(pta: PTA, pag: ImportedGraph): Map<Int, Set<Int>> =
        pag.graph.varIds().associateWith { v ->
            pta.reachingObjects(pag.nodes[v]).toCIPointsToSet().toCollection().map { pag.idOf(it) }.toSet()
        }

    private fun rootId(pta: PTA, pag: ImportedGraph): Int =
        pag.idOf(pta.rootNode.let { if (it is qilin.core.pag.ContextAllocNode) it.base() else it })

    @Test
    fun typedUcfsEqualsSparkForEveryVariable() {
        for (mc in PROGRAMS) {
            val pta = QilinSession.runSpark(mc)
            val pag = ImportedGraph.build(pta)
            val spark = sparkSets(pta, pag)
            val root = rootId(pta, pag)
            assertTrue(root >= 0, "$mc: ROOT is a vertex of the exported graph")
            val typed = TypedPointsToShared.solve(TypedGraph.build(pta, pag)).pointsTo
            var pairs = 0
            for (v in pag.graph.varIds()) {
                val mine = (typed[v] ?: emptySet()) - root
                pairs += mine.size
                assertEquals(spark.getValue(v), mine, "$mc: UCFS-typed vs Spark at ${describe(pag.nodes[v])}")
            }
            assertTrue(pairs > 0, "$mc: non-empty result")
        }
    }

    @Test
    fun importOfSparkSetsReproducesSparkMetrics() {
        for (mc in PROGRAMS) {
            val pta = QilinSession.runSpark(mc)
            val pag = ImportedGraph.build(pta)
            val before = PointsToSetsImport.qilinMetrics(pta)
            assertEquals(PointsToSetsImport.METRICS.size, before.size, "$mc: all metrics parsed: $before")
            val sets = PointsToSetsImport.current(pta, pag)
            PointsToSetsImport.inject(pta, pag, sets)
            assertEquals(before, PointsToSetsImport.qilinMetrics(pta), "$mc: metrics after re-import of Spark's own sets")
        }
    }

    @Test
    fun importOfTypedUcfsSetsGivesSparkMetrics() {
        for (mc in PROGRAMS) {
            val pta = QilinSession.runSpark(mc)
            val pag = ImportedGraph.build(pta)
            val before = PointsToSetsImport.qilinMetrics(pta)
            val typed = TypedPointsToShared.solve(TypedGraph.build(pta, pag)).pointsTo
            PointsToSetsImport.inject(pta, pag, typed)
            assertEquals(before, PointsToSetsImport.qilinMetrics(pta), "$mc: metrics of imported UCFS-typed sets")
        }
    }

    @Test
    fun importReallyReplacesTheSets() {
        // guards against an import that silently keeps Spark's sets: importing nothing must change the metric
        val pta = QilinSession.runSpark(PROGRAMS[0])
        val pag = ImportedGraph.build(pta)
        val before = PointsToSetsImport.qilinMetrics(pta)
        PointsToSetsImport.inject(pta, pag, emptyMap())
        val after = PointsToSetsImport.qilinMetrics(pta)
        val key = "#Avg Points-to Target without Native Var(CI):"
        assertTrue(before[key] != after[key], "empty import changes $key: before=$before after=$after")
    }
}
