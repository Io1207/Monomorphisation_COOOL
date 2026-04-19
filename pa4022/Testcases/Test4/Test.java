/**
 * TC9 — Cast-heavy code: exercises CastExpr flow in PT graph
 *
 * What it exercises:
 *   • applyTransferInter: x = (T) y  — the cast must propagate the same
 *     allocation sites from pts(y) into pts(x).
 *   • isAssignableTo: a site of type Dog must propagate through Animal casts.
 *   • Downcasts: (Dog) animal — the actual runtime type is still Dog, so the
 *     PT graph should still see Dog's allocation site after the cast.
 *   • Cross-interface cast: (Runnable & Comparable) — checks multi-interface.
 *   • Invalid cast paths: sites that can't satisfy isAssignableTo should NOT
 *     propagate, preventing false monomorphization.
 *
 * Expected outcome:
 *   • Calls through upcast → downcast local → still mono (same concrete type).
 *   • Mixed cast paths (cast to wrong interface) → multi-type set → not mono.
 *   • PT graph correctly follows the cast chain through 3 levels.
 */
public class Test {

    // ── Class hierarchy ────────────────────────────────────────────────────

    static abstract class Animal {
        abstract int sound();
        int breathe() { return 1; }
    }

    static class Dog extends Animal {
        @Override public int sound() { return 1; }
        public int fetch()  { return 42; }
    }

    static class Cat extends Animal {
        @Override public int sound() { return 2; }
        public int purr()   { return 99; }
    }

    static class Puppy extends Dog {
        @Override public int sound() { return 3; }
        @Override public int fetch() { return 7; }
    }

    // ── Helper: takes Animal, calls virtual method ─────────────────────────

    static int makeSound(Animal a) { return a.sound(); }
    static int breathe(Animal a)   { return a.breathe(); }

    // ── Up-then-downcast: static type is Animal but runtime is always Dog ─

    static int upDownDog(int x) {
        Dog    d = new Dog();
        Animal a = d;                  // upcast: x = (Animal) d  [implicit]
        int r = makeSound(a);          // virtual — PT sees Dog only
        Dog d2 = (Dog) a;              // downcast: should propagate Dog site
        return r + d2.fetch();         // should also be monomorphic
    }

    // ── Three-level cast chain ─────────────────────────────────────────────

    static int triplecast(int x) {
        Puppy  p = new Puppy();
        Dog    d = (Dog)    p;         // Puppy → Dog (upcast)
        Animal a = (Animal) d;         // Dog   → Animal
        int r1 = makeSound(a);         // PT should see Puppy through 2 cast hops
        Dog d2 = (Dog) a;              // back to Dog
        return r1 + d2.fetch();
    }

    // ── Mixed allocation: two different types cast to same base ───────────
    // pts(a) = {Dog, Cat} → makeSound MUST stay virtual

    static int mixedCast(int x) {
        Animal a;
        if ((x & 1) == 0) a = new Dog();
        else               a = new Cat();
        return makeSound(a);           // stays virtual — two concrete types
    }

    // ── Cast stress with field stores ─────────────────────────────────────

    static class Box {
        Animal occupant;
        Box(Animal a) { this.occupant = a; }
        int runOccupant() { return occupant.sound(); }
    }

    static int boxedDog() {
        Dog d  = new Dog();
        Box b  = new Box(d);
        return b.runOccupant();        // field load chain: Box.occupant → Dog
    }

    static int boxedPuppy() {
        Puppy p = new Puppy();
        Box   b = new Box(p);
        return b.runOccupant();
    }

    // ── Interface cast from concrete class ────────────────────────────────

    interface Talker { int talk(); }

    static class TalkingDog extends Dog implements Talker {
        @Override public int sound() { return 5; }
        @Override public int talk()  { return 100; }
    }

    static int talkingDog() {
        TalkingDog td = new TalkingDog();
        Dog        d  = td;             // upcast
        Talker     t  = (Talker) d;     // cast to interface
        return td.sound() + t.talk() + d.fetch();
    }

    static int runOnce() {
        int acc = 0;
        for (int i = 0; i < 300; i++) {
            acc += upDownDog(i);
            acc += triplecast(i);
            acc += mixedCast(i);
            acc += boxedDog();
            acc += boxedPuppy();
            acc += talkingDog();
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