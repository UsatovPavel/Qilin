package qilin.ucfs.results

import org.ucfs.sppf.node.RangeSppfNode

/** Result of one UCFS points-to query: for every start variable, the set of allocation vertices. */
class UcfsResult(
    val pointsTo: Map<Int, Set<Int>>,
    val roots: Set<RangeSppfNode<Int>>,
    val graphMillis: Long,
    val solveMillis: Long,
) {
    val pairCount: Int get() = pointsTo.values.sumOf { it.size }
}
