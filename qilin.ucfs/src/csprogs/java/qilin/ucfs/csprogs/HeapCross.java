package qilin.ucfs.csprogs;

/** Fields of two objects written by one setter: with a context-insensitive heap (variant A) the values are merged. */
public class HeapCross {
    static class Box {
        Object f;
    }

    static void set(Box b, Object v) {
        b.f = v;
    }

    public static void main(String[] args) {
        Box a = new Box();
        Box b = new Box();
        Object o1 = new Object();
        Object o2 = new Object();
        set(a, o1);
        set(b, o2);
        Object x = a.f;
        Check.mayAlias(x, o1);
    }
}
