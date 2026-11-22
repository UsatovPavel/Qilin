package qilin.ucfs.benchmark

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.BeforeClass
import org.junit.Test
import qilin.ucfs.QilinSession
import qilin.ucfs.graph.ImportedGraph
import qilin.ucfs.graph.TypedGraph
import qilin.ucfs.ucfs.RsmSolve
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RsmProfileTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp()
    }

    @Test
    fun profilingCountsDescriptorsAndPreservesPointsToWithCurrentUcfsStorage() {
        val pta = QilinSession.runSpark(CorePagTest.FS1)
        val pag = ImportedGraph.build(pta)
        val graph = TypedGraph.build(pta, pag)
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        val result = try {
            System.setOut(PrintStream(bytes, true, Charsets.UTF_8))
            RsmSolve.solve(graph, "v2+s+nf", pag.graph.varIds(), reduce = false, reachOnly = true, profile = true)
        } finally {
            System.setOut(previous)
        }
        val comparison = SparkCompare.compare(SparkCompare.sparkSets(pta, pag), result.pointsTo, setOf(SparkCompare.rootId(pta, pag)))
        assertTrue(comparison.diffs.isEmpty(), "profiling must preserve Spark-equivalent answers")
        val output = bytes.toString(Charsets.UTF_8)
        val total = Regex("PROFILE descriptors=(\\d+)").find(output)!!.groupValues[1].toLong()
        val perRole = output.lineSequence().filter { it.startsWith("PROFILE-STATE ") }.sumOf { it.substringAfterLast(' ').toLong() }
        assertTrue(total > 0, "the profile must report actual descriptors")
        assertEquals(total, perRole, "every handled descriptor belongs to one reported role")
    }
}
