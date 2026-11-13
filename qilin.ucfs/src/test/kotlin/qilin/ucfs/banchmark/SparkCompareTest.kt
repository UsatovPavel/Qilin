package qilin.ucfs.banchmark

import qilin.ucfs.graph.CiPag
import qilin.ucfs.QilinSession
import qilin.ucfs.graph.TypedGraph
import qilin.ucfs.ucfs.TypedPointsTo
import qilin.ucfs.graph.describe

import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sanity of the M4 oracle tools: ablation switches, derived call graph. */
class SparkCompareTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp()
    }

    private fun norm(m: Map<Int, Set<Int>>, root: Int) = m.mapValues { it.value - root }.filterValues { it.isNotEmpty() }

    @Test
    fun allFiltersOffIsPlainAndersen() {
        for (mc in TypedSparkTest.PROGRAMS) {
            val pta = QilinSession.runSpark(mc)
            val pag = CiPag.build(pta)
            val root = SparkCompare.rootId(pta, pag)
            val allOff = TypedPointsTo.solve(TypedGraph.build(pta, pag, TypedGraph.Filter.values().toSet())).pointsTo
            assertEquals(norm(ReferenceAndersen.solve(pag.graph), root), norm(allOff, root), "$mc: typed with every filter off = Andersen")
        }
    }

    @Test
    fun derivedCallGraphOfSparkSetsIsSparkCallGraph() {
        var sites = 0
        for (mc in TypedSparkTest.PROGRAMS) {
            val pta = QilinSession.runSpark(mc)
            val pag = CiPag.build(pta)
            val spark = SparkCompare.sparkSets(pta, pag)
            val cg = SparkCompare.derivedCallGraph(pta, pag, spark)
            assertEquals(0, cg.missing, "$mc: missing edges")
            assertEquals(0, cg.extra, "$mc: extra edges")
            sites += cg.sites
            val typed = TypedPointsTo.solve(TypedGraph.build(pta, pag)).pointsTo
            val cgT = SparkCompare.derivedCallGraph(pta, pag, typed)
            assertEquals(0, cgT.missing + cgT.extra, "$mc: call graph derived from UCFS-typed = Spark's")
        }
        assertTrue(sites > 0)
    }

    @Test
    fun compareCategories() {
        val spark = mapOf(1 to setOf(10, 11), 2 to setOf(10), 3 to setOf(10), 4 to setOf(10, 11), 5 to emptySet())
        val mine = mapOf(1 to setOf(10, 11), 2 to setOf(10, 12), 3 to emptySet(), 4 to setOf(10, 12, 99), 5 to setOf(99))
        val c = SparkCompare.compare(spark, mine, ignore = setOf(99))
        assertEquals(listOf(2, 1, 1, 1), listOf(c.equal, c.superset, c.subset, c.incomparable))
    }
}

class TypedVariantsTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp()
    }

    @Test
    fun sharedAndSubsetGrammarsAgreeAlsoUnderAblation() {
        for (mc in TypedSparkTest.PROGRAMS) {
            val pta = QilinSession.runSpark(mc)
            val pag = CiPag.build(pta)
            for (off in listOf(emptySet<TypedGraph.Filter>()) + TypedGraph.Filter.values().map { setOf(it) }) {
                val tg = TypedGraph.build(pta, pag, off)
                val a = TypedPointsTo.solve(tg, variant = TypedPointsTo.Variant.SHARED).pointsTo
                val b = TypedPointsTo.solve(tg, variant = TypedPointsTo.Variant.SUBSET).pointsTo
                assertEquals(b, a, "$mc off=$off: grammar v2 = grammar v1")
            }
        }
    }
}

class ReductionTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp()
    }

    @Test
    fun reducedGraphGivesTheSameSetsAlsoUnderAblation() {
        var merged = 0
        for (mc in TypedSparkTest.PROGRAMS) {
            val pta = QilinSession.runSpark(mc)
            val pag = CiPag.build(pta)
            for (off in listOf(emptySet<TypedGraph.Filter>()) + TypedGraph.Filter.values().map { setOf(it) } + listOf(TypedGraph.Filter.values().toSet())) {
                val tg = TypedGraph.build(pta, pag, off)
                val full = TypedPointsTo.solve(tg).pointsTo
                val red = TypedPointsTo.solve(tg, reduce = true)
                assertEquals(full, red.pointsTo, "$mc off=$off: reduction is exact")
                merged += pag.graph.varIds().size - red.reducedStarts
            }
        }
        assertTrue(merged > 0, "the reduction merges some variables")
    }
}

class ReachOnlyTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp()
    }

    @Test
    fun reachabilityOnlyModeGivesTheSameSets() {
        for (mc in TypedSparkTest.PROGRAMS) {
            val pta = QilinSession.runSpark(mc)
            val pag = CiPag.build(pta)
            for (off in listOf(emptySet<TypedGraph.Filter>(), TypedGraph.Filter.values().toSet())) {
                val tg = TypedGraph.build(pta, pag, off)
                assertEquals(TypedPointsTo.solve(tg).pointsTo, TypedPointsTo.solve(tg, reachOnly = true).pointsTo, "$mc off=$off")
                assertEquals(TypedPointsTo.solve(tg, reduce = true).pointsTo, TypedPointsTo.solve(tg, reduce = true, reachOnly = true).pointsTo, "$mc off=$off reduce")
            }
        }
    }
}

/** D12 admits exactly one difference: the fake ROOT object, and only in FakeMain's variables. */
class RootOnlyInFakeMainTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp()
    }

    @Test
    fun rootReachesOnlyFakeMainVariables() {
        for (mc in TypedSparkTest.PROGRAMS) {
            val pta = QilinSession.runSpark(mc)
            val pag = CiPag.build(pta)
            val root = SparkCompare.rootId(pta, pag)
            val typed = TypedPointsTo.solve(TypedGraph.build(pta, pag)).pointsTo
            val holders = typed.filterValues { root in it }.keys
            assertTrue(holders.isNotEmpty(), "$mc: ROOT reaches FakeMain.this")
            val fakeMain = qilin.core.PTAScene.v().fakeMainMethod
            for (v in holders) {
                val n = pag.nodes[v]
                assertTrue(n is qilin.core.pag.LocalVarNode && n.method == fakeMain, "$mc: ROOT only in FakeMain variables, found at ${describe(n)}")
            }
        }
    }
}
