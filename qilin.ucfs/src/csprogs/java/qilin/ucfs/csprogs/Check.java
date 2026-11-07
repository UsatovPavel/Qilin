package qilin.ucfs.csprogs;

/**
 * Alias assertions of the M14 acceptance programs (Cut-Shortcut patterns), read by the Kotlin tests like
 * Qilin's own `qilin.microben.utils.Assert`; [flag] and [g] are opaque to the analysis.
 */
public class Check {
    public static boolean flag;
    public static Object g;

    public static void mayAlias(Object a, Object b) {
    }

    public static void notAlias(Object a, Object b) {
    }
}
