public class Test {

    static void runOnce() {
      Container container = new Container();
        
        // Complex setup: hiding the target object inside a field, 
        // then passing that field to a helper
        container.item = new SecretWorker();
        
        // Pass the item to a helper to obfuscate the flow
        Worker w = getWorker(container);
        
        // Monomorphization point: 
        // PTGraph must prove 'w' is definitely a SecretWorker,
        // even though it came from a field and a helper method.
        w.perform();
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

    public static Worker getWorker(Container c) {
        return c.item;
    }
}

interface Worker {
    void perform();
}

class SecretWorker implements Worker {
    public void perform() {
        System.out.println("Secret logic executed!");
    }
}

class Container {
    Worker item;
}