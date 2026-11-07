package qilin.ucfs.csprogs;

/** Unmatched return at the start of a path (object made in a callee) and unmatched call at its end (value seen in a callee). */
public class PartialBalance {
    static Object mk() {
        return new Object();
    }

    static void look(Object p) {
        Object q = Check.g;
        Check.mayAlias(p, q);
    }

    public static void main(String[] args) {
        Object x = mk();
        Object y = mk();
        Check.mayAlias(x, y);
        Check.g = x;
        look(x);
    }
}
