package qilin.ucfs

import org.junit.Test
import qilin.pta.tools.DebloatedPTA
import kotlin.test.assertEquals

/** Critic M15/M16 (M15-1): oracle specs keep Qilin's own suffixes (`+0h`) and only the debloating suffixes are removed. */
class OraclePatternTest {
    @Test
    fun heapContextSuffixIsKept() {
        for ((spec, k, hk) in listOf(Triple("1c+0h", 1, 0), Triple("2c+0h", 2, 0), Triple("3c+0h", 3, 0), Triple("2c", 2, 1), Triple("2o", 2, 1))) {
            val (p, d) = QilinSession.patternOf(spec)
            assertEquals(k, p.contextDepth, "$spec: context depth"); assertEquals(hk, p.heapContextDepth, "$spec: heap context depth")
            assertEquals(null, d, "$spec: no debloating")
        }
    }

    @Test
    fun debloatingSuffixesAreRemoved() {
        val (p1, d1) = QilinSession.patternOf("2o+D"); assertEquals(1, p1.heapContextDepth); assertEquals(DebloatedPTA.DebloatApproach.CONCH, d1)
        val (p2, d2) = QilinSession.patternOf("2o+DX"); assertEquals(1, p2.heapContextDepth); assertEquals(DebloatedPTA.DebloatApproach.DEBLOATERX, d2)
    }
}
