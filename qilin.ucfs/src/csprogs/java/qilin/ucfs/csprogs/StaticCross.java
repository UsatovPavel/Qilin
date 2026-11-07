package qilin.ucfs.csprogs;

/** A flow through a static field between two calling contexts: argument of put -> static -> result of get (another call site). */
public class StaticCross {
    static Object g;

    static void put(Object p) {
        g = p;
    }

    static Object get() {
        return g;
    }

    public static void main(String[] args) {
        Object o = new Object();
        put(o);
        Object x = get();
        Check.mayAlias(x, o);
    }
}
