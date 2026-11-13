package qilin.ucfs.results

class TypedPointsToResult(val pointsTo: Map<Int, Set<Int>>, val graphMillis: Long, val solveMillis: Long, val constraintStates: Int, val roots: Int) {
    /** with reduce = true: number of distinct representative start vertices and edges of the reduced graph */
    var reducedStarts = -1
    var reducedEdges = -1
}
