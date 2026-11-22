package qilin.ucfs.benchmark

import org.junit.Test
import kotlin.test.assertEquals

class UcfsSmokeTest {
    @Test
    fun anbnOnChainFindsExactlyBalancedPairs() {
        // 0 -a-> 1 -a-> 2 -b-> 3 -b-> 4 : a^n b^n paths are 1->3 (ab) and 0->4 (aabb).
        val edges = listOf(Triple(0, "a", 1), Triple(1, "a", 2), Triple(2, "b", 3), Triple(3, "b", 4))
        assertEquals(setOf(1 to 3, 0 to 4), UcfsSmoke.reachablePairs(AnBnGrammar(), edges))
    }

    @Test
    fun anbnOnCycleFindsUnbalancedCounts() {
        // a-loop on 0 then 0 -b-> 1 -b-> 2: a^n b^n paths are 0->1 (ab) and 0->2 (aabb).
        val edges = listOf(Triple(0, "a", 0), Triple(0, "b", 1), Triple(1, "b", 2))
        assertEquals(setOf(0 to 1, 0 to 2), UcfsSmoke.reachablePairs(AnBnGrammar(), edges))
    }
}
