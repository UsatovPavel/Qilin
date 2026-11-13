package qilin.ucfs.banchmark

import qilin.ucfs.graph.CiEdge
import qilin.ucfs.graph.ConstraintGraph
import qilin.ucfs.graph.EdgeKind
import qilin.ucfs.graph.EdgeOrigin

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals

class ReferenceAndersenSolverTest {
    @Test
    fun worklistSolverEqualsNaiveOracleOnRandomGraphs() {
        val rnd = Random(42)
        repeat(100) { round ->
            val allocs = 1 + rnd.nextInt(6)
            val vars = 3 + rnd.nextInt(25)
            val fields = 1 + rnd.nextInt(4)
            val n = allocs + vars
            fun v() = allocs + rnd.nextInt(vars)
            val edges = (0 until 10 + rnd.nextInt(50)).map {
                when (rnd.nextInt(4)) {
                    0 -> CiEdge(EdgeKind.ALLOC, rnd.nextInt(allocs), v(), -1, EdgeOrigin.INTRA)
                    1 -> CiEdge(EdgeKind.ASSIGN, v(), v(), -1, EdgeOrigin.INTRA)
                    2 -> CiEdge(EdgeKind.STORE, v(), v(), rnd.nextInt(fields), EdgeOrigin.INTRA)
                    else -> CiEdge(EdgeKind.LOAD, v(), v(), rnd.nextInt(fields), EdgeOrigin.INTRA)
                }
            }.distinct()
            val g = ConstraintGraph(n, BooleanArray(n) { it < allocs }, fields, edges)
            assertEquals(ReferenceAndersen.solve(g), ReferenceAndersenSolver.solve(g), "round $round")
        }
    }
}
