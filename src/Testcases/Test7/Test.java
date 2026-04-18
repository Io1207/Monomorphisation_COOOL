class Bottles {
    int x;
    Bottles() {
        x = 10;
    }
    Water ret_water() {
        return (new Water());
    }
    Bottles ret_bot() {
        return (new Wine());
    }
    void dummy() {
        System.out.println("bottle " + x);
    }
}

class Water extends Bottles {
    int y;
    Water() {
        y = 20;
    }
    Bottles ret_bot() {
        return (new Wine());
    }
    void dummy() {
        System.out.println("water " + y);
    }
}

class Wine extends Bottles {
    /*
        I do not endorse drinking but this was what came into my minds
    */
    int z;
    Wine() {
        z = 30;
    }
    Bottles ret_bot() {
        return (new Bottles());
    }
    void dummy() {
        System.out.println("wine " + z);
    }
}

public class Test {
    static int runOnce() {
        Bottles b  = new Bottles();
        Bottles w  = b.ret_water();
        Bottles b2 = w.ret_bot();
        Bottles b3 = b2.ret_bot();
        b.dummy();
        w.dummy();
        b2.dummy();
        b3.dummy();
        // return something so the JVM can't eliminate the calls as dead code
        return b.x + b2.x + b3.x;
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
