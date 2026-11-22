package qilin.ucfs.benchmark.csprogs;

/** Negative: a recursive setter (the method is in a cycle of the call graph). */
public class Recursive {
    static class Box {
        Object f;

        void set(Object v, int n) {
            if (n > 0) {
                this.set(v, n - 1);
            }
            this.f = v;
        }
    }

    public static void main(String[] args) {
        Box b1 = new Box();
        Box b2 = new Box();
        Object o1 = new Object();
        Object o2 = new Object();
        b1.set(o1, 3);
        b2.set(o2, 3);
        Object x1 = b1.f;
        Object x2 = b2.f;
        Check.mayAlias(x1, o1);
        Check.mayAlias(x2, o2);
    }
}
