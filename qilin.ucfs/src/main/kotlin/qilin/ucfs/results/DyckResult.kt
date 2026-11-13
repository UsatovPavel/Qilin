package qilin.ucfs.results

class DyckResult(val pointsTo: Map<Int, Set<Int>>, val graphMillis: Long, val solveMillis: Long, val states: Int, val roots: Int, val liveHeapMb: Long)
