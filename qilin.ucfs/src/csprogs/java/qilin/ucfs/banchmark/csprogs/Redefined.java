package qilin.ucfs.banchmark.csprogs;

/** Negative: the stored parameter is redefined in the setter, so its values do not all come from the call sites. */
public class Redefined {
    static class Box {
        Object f;

        void set(Object v) {
            if (Check.flag) {
                v = Check.g;
            }
            this.f = v;
        }
    }

    public static void main(String[] args) {
        Object og = new Object();
        Check.g = og;
        Box b1 = new Box();
        Box b2 = new Box();
        Object o1 = new Object();
        Object o2 = new Object();
        b1.set(o1);
        b2.set(o2);
        Object x1 = b1.f;
        Check.mayAlias(x1, o1);
        Check.mayAlias(x1, og);
    }
}
