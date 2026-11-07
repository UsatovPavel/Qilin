package qilin.ucfs.csprogs;

/** Ma et al. PLDI'23, Fig. 1: a setter and a getter called on two objects. */
public class FieldAccess {
    static class Box {
        Object item;

        void setItem(Object item) {
            this.item = item;
        }

        Object getItem() {
            Object r = this.item;
            return r;
        }
    }

    public static void main(String[] args) {
        Box c1 = new Box();
        Box c2 = new Box();
        Object i1 = new Object();
        Object i2 = new Object();
        c1.setItem(i1);
        c2.setItem(i2);
        Object r1 = c1.getItem();
        Object r2 = c2.getItem();
        Object d1 = c1.item;
        Check.mayAlias(r1, i1);
        Check.mayAlias(r2, i2);
        Check.mayAlias(d1, i1);
        Check.notAlias(r1, r2);
        Check.notAlias(d1, i2);
    }
}
