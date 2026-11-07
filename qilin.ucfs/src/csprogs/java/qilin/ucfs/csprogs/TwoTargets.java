package qilin.ucfs.csprogs;

/** Negative: one call site dispatches to two setters; each must receive only the objects that select it. */
public class TwoTargets {
    abstract static class S {
        Object f;

        abstract void set(Object v);
    }

    static class S1 extends S {
        void set(Object v) {
            this.f = v;
        }
    }

    static class S2 extends S {
        Object g;

        void set(Object v) {
            this.g = v;
        }
    }

    public static void main(String[] args) {
        S1 a = new S1();
        S2 b = new S2();
        S s = Check.flag ? (S) a : (S) b;
        Object o1 = new Object();
        s.set(o1);
        Object o2 = new Object();
        a.set(o2);
        Object x = a.f;
        Object y = b.g;
        Object z = b.f;
        Check.mayAlias(x, o1);
        Check.mayAlias(x, o2);
        Check.mayAlias(y, o1);
        Check.notAlias(z, o1);
        Check.notAlias(y, o2);
    }
}
