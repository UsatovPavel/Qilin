package qilin.ucfs

import driver.Main
import driver.PTAFactory
import driver.PTAOption
import driver.PTAPattern
import qilin.core.PTA
import qilin.core.PTAScene
import qilin.pta.PTAConfig
import qilin.pta.tools.DebloatedPTA
import soot.options.Options

/**
 * Drives Qilin from Kotlin: parse Qilin CLI options, set up Soot once, then run the
 * context-insensitive core analysis (Spark, `-pta=insens`) for a main class.
 *
 * Qilin keeps global singletons (PTAConfig, PTAScene, Soot Scene), so one session per JVM
 * application path; [runSpark] may be called for several main classes of that path
 * (the same pattern as `qilin.test.util.JunitTests`).
 */
object QilinSession {
    private var setUpFor: List<String>? = null

    /** Parses Qilin options (same syntax as `driver.Main`) and loads classes into Soot. */
    fun setUp(args: List<String>) {
        if (setUpFor == args) return
        check(setUpFor == null) { "Qilin/Soot are global singletons: one setUp per JVM (was $setUpFor)" }
        PTAOption().parseCommandLine(args.toTypedArray())
        Main.setupSoot()
        setUpFor = args
    }

    /**
     * Runs another Qilin analysis ([pattern], e.g. `2c`) on the current main class after a scene reset, as the Qilin
     * JUnit tests do between runs. Nodes of an earlier analysis stay valid as objects but belong to its own PAG.
     */
    fun runPattern(pattern: String): PTA {
        checkNotNull(setUpFor) { "call setUp first" }
        PTAScene.junitReset()
        val (p, debloat) = patternOf(pattern)
        val cfg = PTAConfig.v().ptaConfig
        cfg.ptaPattern = p
        cfg.ptaName = p.toString()
        cfg.ctxDebloating = debloat != null
        if (debloat != null) cfg.debloatApproach = debloat
        try {
            val pta = PTAFactory.createPTA(p)
            pta.pureRun()
            // DebloatedPTA keeps the solved analysis in its base PTA (its own PAG stays empty): return that one
            if (pta is DebloatedPTA) {
                val f = DebloatedPTA::class.java.getDeclaredField("basePTA").also { it.isAccessible = true }
                return f.get(pta) as PTA
            }
            return pta
        } finally {
            cfg.ctxDebloating = false
        }
    }

    /**
     * Qilin pattern and context debloating of an oracle spec: suffix +D / +DX — debloating (Conch / DebloaterX), as `-cd -cda ...`
     * of driver.Main (naming of M12). Only these suffixes are removed: Qilin's own (`2c+0h`) are part of the pattern (critic M15/M16).
     */
    fun patternOf(pattern: String): Pair<PTAPattern, DebloatedPTA.DebloatApproach?> {
        val debloat = when {
            pattern.endsWith("+DX") -> DebloatedPTA.DebloatApproach.DEBLOATERX
            pattern.endsWith("+D") -> DebloatedPTA.DebloatApproach.CONCH
            else -> null
        }
        val base = when (debloat) { DebloatedPTA.DebloatApproach.DEBLOATERX -> pattern.removeSuffix("+DX"); null -> pattern; else -> pattern.removeSuffix("+D") }
        return PTAPattern(base) to debloat
    }

    /** Runs Spark (`insens`) on [mainClass] and returns the solved analysis. */
    fun runSpark(mainClass: String? = null, callDetails: Boolean = false): PTA {
        checkNotNull(setUpFor) { "call setUp first" }
        if (mainClass != null) {
            PTAScene.junitReset()
            PTAConfig.v().appConfig.MAIN_CLASS = mainClass
            Options.v().set_main_class(mainClass)
            PTAScene.v().setMainClass(PTAScene.v().getSootClass(mainClass))
        }
        val pattern = PTAPattern("insens")
        PTAConfig.v().ptaConfig.ptaPattern = pattern
        PTAConfig.v().ptaConfig.ptaName = pattern.toString()
        val pta = PTAFactory.createPTA(pattern)
        // callDetails: record call details for Zipper's pre-analysis (as ZipperPTA does around its Spark run; M16)
        if (callDetails) qilin.util.CallDetails.v().enable()
        try { pta.pureRun() } finally { if (callDetails) qilin.util.CallDetails.v().disable() }
        return pta
    }
}
