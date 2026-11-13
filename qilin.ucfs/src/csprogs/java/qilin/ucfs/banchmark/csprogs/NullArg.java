package qilin.ucfs.banchmark.csprogs;

/** Negative: a call site passes null; the other site still stores its object. */
public class NullArg {
    static class Box {
        Object f;

        void set(Object v) {
            this.f = v;
        }
    }

    public static void main(String[] args) {
        Box b1 = new Box();
        Box b2 = new Box();
        Object o2 = new Object();
        b1.set(null);
        b2.set(o2);
        Object x2 = b2.f;
        Object x1 = b1.f;
        Check.mayAlias(x2, o2);
        Check.notAlias(x1, o2);
    }
}
