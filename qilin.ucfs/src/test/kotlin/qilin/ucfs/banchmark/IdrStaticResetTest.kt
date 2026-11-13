package qilin.ucfs.banchmark

import qilin.ucfs.graph.CiPag
import qilin.ucfs.idr.IdrExport
import qilin.ucfs.QilinSession

import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertTrue

/** M15 A0 (verifier P1): the call-stack reset at static fields is a property of heap-CI+pb only; vf+pb and heap+pb are unchanged (M7). */
class IdrStaticResetTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CutShortcutTest.setUp()
    }

    @Test
    fun onlyHeapCiPbHasLoopsAtStaticFields() {
        val pta = QilinSession.runSpark("qilin.ucfs.banchmark.csprogs.StaticCross")
        val pag = CiPag.build(pta)
        val globals = pag.nodes.indices.filter { pag.nodes[it] is qilin.core.pag.GlobalVarNode }.toSet()
        assertTrue(globals.isNotEmpty())
        for (enc in IdrExport.Encoding.values()) {
            val idr = IdrExport.build(pta, pag, enc)
            val loops = idr.edges.count { it.src == it.dst && it.src in globals }
            if (enc == IdrExport.Encoding.HEAPCIPB) assertTrue(loops > 0, "$enc: loops at static fields")
            else assertTrue(loops == 0, "$enc: no loops at static fields (as in M7), got $loops")
        }
    }
}
