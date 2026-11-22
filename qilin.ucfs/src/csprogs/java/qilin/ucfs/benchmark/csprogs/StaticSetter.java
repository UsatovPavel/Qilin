package qilin.ucfs.benchmark.csprogs;

/** A static setter/getter pair: base and value are both ordinary parameters. */
public class StaticSetter {
    static class Box {
        Object f;
    }

    static void put(Box b, Object v) {
        b.f = v;
    }

    static Object take(Box b) {
        return b.f;
    }

    public static void main(String[] args) {
        Box b1 = new Box();
        Box b2 = new Box();
        Object o1 = new Object();
        Object o2 = new Object();
        put(b1, o1);
        put(b2, o2);
        Object x1 = take(b1);
        Object x2 = take(b2);
        Check.mayAlias(x1, o1);
        Check.mayAlias(x2, o2);
        Check.notAlias(x1, x2);
    }
}
