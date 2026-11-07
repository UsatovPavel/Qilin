package qilin.ucfs

import org.junit.BeforeClass
import org.junit.Test
import qilin.core.pag.AllocNode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M7 D7.2: pairs read from a cfl-idr output reach Qilin's `reachingObjects` through the encoding and the import. */
class IdrImportTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp()
    }

    @Test
    fun parsedPairsAreImportedAsPointsToSets() {
        val pta = QilinSession.runSpark(CorePagTest.FS1)
        val pag = CiPag.build(pta)
        for (enc in IdrExport.Encoding.values()) {
            val idr = IdrExport.build(pta, pag, enc)
            // a synthetic cfl-idr output: every Spark fact (o, x), written as the pair of this encoding
            val spark = SparkCompare.sparkSets(pta, pag)
            val n = pag.nodes.size
            val src = HashMap<Int, Int>(); val sink = HashMap<Int, Int>()
            val f = File.createTempFile("idr", ".dot"); idr.writeDot(f)
            val map = File(f.path.removeSuffix(".dot") + ".map.tsv")
            IdrRun.checkMap(idr, map)
            for (l in map.readLines().drop(1)) {
                val (i, k, b) = l.split("\t", limit = 3)
                if (k == "src") src[b.toInt()] = i.toInt()
                if (k == "sink") sink[b.toInt()] = i.toInt()
            }
            val pairs = spark.flatMap { (x, os) -> os.map { o -> if (enc == IdrExport.Encoding.VF) o to x else src.getValue(o) to sink.getValue(x) } }
            val text = "Under approximation paths: ${pairs.size}\n\nOver approximation paths (parity): 0\n\n" +
                "Under approximation path list:\n" + pairs.joinToString("") { "\t${it.first} ${it.second}\n" }
            val parsed = IdrRun.parse(text)
            assertEquals(pairs.toSet(), parsed.under)
            val pts = IdrRun.pointsTo(idr, parsed.under)
            assertEquals(spark.filterValues { it.isNotEmpty() }, pts, "$enc: encoding maps the pairs back")
            PtsImport.inject(pta, pag, pts)
            var checked = 0
            for ((x, os) in pts) {
                val got = pta.reachingObjects(pag.nodes[x]).toCIPointsToSet().toCollection().map { pag.idOf(it as AllocNode) }.toSet()
                assertEquals(os, got, "$enc: reachingObjects after import"); checked++
            }
            assertTrue(checked > 0)
        }
    }
}

/** D30: the import must replace Qilin's sets (not keep Spark's), and a map that does not match the export must be rejected. */
class IdrImportReplacesTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp()
    }

    @Test
    fun importedSetsDifferentFromSparkAreWhatQilinSees() {
        val pta = QilinSession.runSpark(CorePagTest.FS1)
        val pag = CiPag.build(pta)
        val spark = SparkCompare.sparkSets(pta, pag).filterValues { it.isNotEmpty() }
        // drop the smallest object id from every set (and empty every third variable): differs from Spark everywhere
        val changed = spark.entries.mapIndexed { i, (v, os) -> v to (if (i % 3 == 0) emptySet() else os - os.min()) }.toMap()
        assertTrue(changed.any { (v, os) -> os != spark.getValue(v) })
        PtsImport.inject(pta, pag, changed.filterValues { it.isNotEmpty() })
        for ((v, os) in changed) {
            val got = pta.reachingObjects(pag.nodes[v]).toCIPointsToSet().toCollection().map { pag.idOf(it as AllocNode) }.toSet()
            assertEquals(os, got, "reachingObjects after import at ${describe(pag.nodes[v])}")
        }
    }

    @Test
    fun tamperedMapIsRejected() {
        val pta = QilinSession.runSpark(CorePagTest.FS1)
        val pag = CiPag.build(pta)
        for (enc in IdrExport.Encoding.values()) {
            val idr = IdrExport.build(pta, pag, enc)
            val f = File.createTempFile("idr", ".dot"); idr.writeDot(f)
            val map = File(f.path.removeSuffix(".dot") + ".map.tsv")
            IdrRun.checkMap(idr, map) // the genuine map passes
            val lines = map.readLines()
            val tampered = listOf(
                lines.toMutableList().also { it[1] = it[1].replaceFirst(Regex("^\\d+"), "99999") },            // wrong id
                lines.toMutableList().also { it[2] = if ("\tvar\t" in it[2]) it[2].replace("\tvar\t", "\talloc\t") else it[2].replace("\talloc\t", "\tvar\t") }, // kind flip
                lines.toMutableList().also { val t = it[1]; it[1] = it[2]; it[2] = t },                       // swapped rows
            ) + (if (idr.vertexCount > pag.nodes.size) listOf(lines.toMutableList().also {                   // wrong base of an extra vertex
                val i = pag.nodes.size + 1; val (id, k, b) = it[i].split("\t"); it[i] = "$id\t$k\t${b.toInt() + 1}" }) else emptyList())
            for ((j, t) in tampered.withIndex()) {
                assertTrue(t != lines, "$enc: tampering #$j changed the map")
                map.writeText(t.joinToString("\n") + "\n")
                assertTrue(runCatching { IdrRun.checkMap(idr, map) }.isFailure, "$enc: tampered map #$j must be rejected")
            }
        }
    }
}
