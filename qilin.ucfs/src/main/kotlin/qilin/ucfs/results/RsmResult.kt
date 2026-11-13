package qilin.ucfs.results

class RsmResult(val pointsTo: Map<Int, Set<Int>>, val graphMillis: Long, val solveMillis: Long, val states: Int, val roots: Int,
              val solveCpuMillis: Long = -1, val liveHeapMb: Long = -1)
