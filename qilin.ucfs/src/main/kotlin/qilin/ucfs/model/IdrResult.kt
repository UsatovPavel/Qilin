package qilin.ucfs.model

class IdrResult(val under: Set<Pair<Int, Int>>, val over: Set<Pair<Int, Int>>?, val onDemand: Set<Pair<Int, Int>>?,
                 val underCount: Int, val overCount: Int, val millis: Long)
