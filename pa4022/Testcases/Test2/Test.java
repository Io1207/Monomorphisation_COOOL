class Animal {
    int x, y;
    Animal () {
        x = 0;
        y = 0;
    }

    void say () {
        System.out.println("Saying something");
        this.x = this.x + 1;
    }

    void befriend(Animal x) {
        System.out.println("this is my friend now");
        x.x = this.x + x.x;
        this.x = this.x * x.x;
    }
}

class Pig extends Animal {
    int y, z;
    Pig () {
        y = 3;
        z = x + 4;
    }

    void say () {
        System.out.println("Oink Oink");
        x = y * 2;
    }

    void sniff_the_garbage () {
        System.out.println("sniff sniff");
        z = z * 3;
    }

    void befriend(Animal x) {
        if (x instanceof Pig) {
            System.out.println("friend");
        }
        if (x instanceof Dog) {
            System.out.println("sus animal");
        }
    }
}

class Dog extends Animal {
    Dog () {
        x = 6;
        y = 7;
    }
    void say () {System.out.println("Bark!");}
    void befriend(Animal x) {
        if (x instanceof Dog) {
            System.out.println("friend");
        }
        if (!(x instanceof Dog)) {
            System.out.println("still friends #golden_retriever_energy");
        }
    }
}

public class Test {
    static Animal complex(Animal a) {
        Animal b = new Animal();
        if (a.x > 12) {
            b = new Dog();
        }
        else if (a.x + 4 < a.y * 4) {
            a.x = a.x + 2;
            b = complex(a);
        }
        else {
            b = new Pig();
        }
        return b;
    }

    static void runOnce () {
        Animal o1 = new Pig();
        o1.x = 3;
        o1.say();
        Animal o2 = complex(o1);
        o2.say();
        Animal o3 = new Animal();
        o3.x = 13;
        Animal o4 = complex(o3);
        // Animal o2 = new Dog();
        // Animal o3 = new Pig();
        // Animal o4 = new Dog();
        o2.befriend(o4);
        o4.befriend(o3);
        o4.say();
    }

    public static void main(String[] args) {
        int iterations = 50000;

        long start = System.nanoTime();

        int acc = 0;
        for (int iter = 0; iter < iterations; iter++) {
          runOnce();
        }

        long end = System.nanoTime();
        System.out.println("Result : " + acc);
        System.out.printf("Time   : %.3f ms%n", (end - start) / 1_000_000.0);
    }
}
