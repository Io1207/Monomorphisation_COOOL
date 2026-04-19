public class Test {
    // ── Tree nodes ─────────────────────────────────────────────────────────
    interface Visitor {
        int visitLeaf(Leaf n);
        int visitInner(Inner n);
    }
 
    interface Node {
        int accept(Visitor v);
        int depth();
    }
 
    static class Leaf implements Node {
        final int value;
        Leaf(int v) { this.value = v; }
        @Override public int accept(Visitor v) { return v.visitLeaf(this); }
        @Override public int depth() { return 0; }
    }
 
    static class Inner implements Node {
        final Node left, right;
        Inner(Node l, Node r) { this.left = l; this.right = r; }
        @Override public int accept(Visitor v) {
            return v.visitInner(this);
        }
        @Override public int depth() {
            int ld = left.depth();
            int rd = right.depth();
            return 1 + (ld > rd ? ld : rd);
        }
    }
 
    // ── Concrete visitors ──────────────────────────────────────────────────
 
    static class SumVisitor implements Visitor {
        @Override public int visitLeaf(Leaf n)  { return n.value; }
        @Override public int visitInner(Inner n) {
            return n.left.accept(this) + n.right.accept(this);
        }
    }
 
    static class MaxVisitor implements Visitor {
        @Override public int visitLeaf(Leaf n)  { return n.value; }
        @Override public int visitInner(Inner n) {
            int l = n.left.accept(this);
            int r = n.right.accept(this);
            return l > r ? l : r;
        }
    }
 
    static class CountVisitor implements Visitor {
        @Override public int visitLeaf(Leaf n)  { return 1; }
        @Override public int visitInner(Inner n) {
            return n.left.accept(this) + n.right.accept(this) + 1;
        }
    }
 
    // ── Builder ────────────────────────────────────────────────────────────
 
    static Node buildTree(int depth, int seed) {
        if (depth == 0) return new Leaf(seed % 100);
        Node left  = buildTree(depth - 1, seed * 2);
        Node right = buildTree(depth - 1, seed * 2 + 1);
        return new Inner(left, right);
    }
 
    // ── Usage with statically known visitor types ──────────────────────────
 
    static int sumTree(Node root) {
        SumVisitor sv = new SumVisitor();
        return root.accept(sv);
    }
 
    static int maxTree(Node root) {
        MaxVisitor mv = new MaxVisitor();
        return root.accept(mv);
    }
 
    static int countNodes(Node root) {
        CountVisitor cv = new CountVisitor();
        return root.accept(cv);
    }
 
    // ── Combined: PT graph must trace field loads across levels ───────────
 
    static int combined(int depth, int seed) {
        Node tree = buildTree(depth, seed);
        return sumTree(tree) + maxTree(tree) + countNodes(tree) + tree.depth();
    }
 
    static int runOnce () {
        int acc = 0;
        for (int i = 0; i < 20; i++) {
            acc += combined(4, i);
        }
        System.out.println(acc);
        return acc;
    }

    public static void main(String[] args) {
        int iterations = 50000;

        long start = System.nanoTime();

        int acc = 0;
        for (int iter = 0; iter < iterations; iter++) {
            acc += runOnce();
        }

        long end = System.nanoTime();
        System.out.println("Result : " + acc);
        System.out.printf("Time   : %.3f ms%n", (end - start) / 1_000_000.0);
    }
}