class M {
  int x, y;
}

class N extends M{
  void foo(M mm) {
    System.out.println(mm.x);
    bar(mm);
  }

  void bar(M mm) {
    System.out.println(mm.y);
  }

  void blah (M m1, M m2, M m3) {
    System.out.println(m1.x);
    m2.y = 354;
  }
}

class O {
  void foo(M mm) {
    System.out.println(mm.x);
  }
  void foo(Integer nn) {
      System.out.println(nn+11);
  }
}

class P extends O {
  void foo(M mm) {
    mm.x = 112;
  }

  void foo (Integer nn) {
      System.out.println(nn);
  }
}


public class Test {
  static M xxx;

  static void runOnce() {
        { // Case 1
          M o1 = new M(); // O31
          o1.x = 8;
          // xxx.x = o1.x;
          // xxx = o1;
          System.out.println(o1.x);
        }

        { // Case 2
          M o2 = new M(); // O37
          N o3 = new N(); // O38
          M o6 = new M();
          M o7 = new M();
          o2.x = 27;
          o2.y = 125;
          o3.foo(o2);
          o3.blah(o2, o6, o7);
        }

        { // Case 3
          M o4 = new N(); // O45
          O o5 = new O(); // O46
          O o6 = new P(); // O47
          o4.x = 343;
          o5.foo(o4);
          // o6.foo(13);
        }

        return;
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

///////////////   2

// class A {
//   int x;
// }

// class Test {
//   static A global;
//   public static void main(String[] args) {
//     A a = new A();
//     A c = new A();
//     A b;
//     if (args.length > 0) b = a;
//     else b = c;
//     b.x = 10;
//     // global = b;
//   }
// }

//////////// 3

// class A {
//   int x;
// }

// class Test {
//   public static void main(String[] args) {
//     A a = new A();
//     A c = new A();
//     A b = a;
//     b.x = 10;
//   }
// }

//////////// 4

// class A {
//   int x;

//   void bc(A c, A b, int q) {
//     if (q > 0) {b = this; b.x =10;}
//     else {b = c; b.x = 10;}
//     int x= b.x;
//   }
// }

// class Test {
//   public static void main(String[] args) {
//     A a = new A();
//     A c = new A();
//     A b = new A();
//     b.x = 1;
//     a.bc(c,b,11);
//   }
// }

//////////// 5

// class A{
//   A f; int g;
// }

// class Test{
//   public static void main(String[] args) {
//     A a = new A();
//     a.f = new A();
//     a.f.g = 92;
//   }
// }

////////// 6

// class A{
//   A f; int g;
// }

// class Test{
//   public static void main(String[] args) {
//     A a = new A();
//     A b = new A();
//     a.f = b;
//     b.g = 10;
//   }
// }

////////// 7

// public class Test {
//   static B global;
//   static class A {int x;}
//   static class B {
//     A a;
//     void foo(B b) {
//       A p = new A();
//       b.a = p;
//       global = b; 
//     }
//   }
//   public static void main(String[] args) {
//     A a = new A();
//     B b = new B();
//     b.a = a;
//     b.foo(b);
//   }
// }

///////////// 8

// class A{A x;}

// class B{
//   A y;
//   void foo(A a) {
//     a.x.x = new A();
//     y = new A();
//   }
// }

// public class Test {
//   public static void main(String[] args) {
//     B b = new B();
//     A a = new A();
//     a.x = new A();
//     b.foo(a);
//   }
// }

//////////// 9

// class Node {
//   Node f1, f2;
//   Node g;

//   Node(){}

//   public void foo(Node x, Node y, int i) {
//     x.f2=y;
//   }
// } 

// public class Test {
//   public static void main(String[] args) {
//     Node a = new Node();
//     a.f1 = new Node();
//     Node b = new Node();
//     b.f1=new Node();
//     a.f2=new Node();
//     a.foo(a,b,1);
//   }
// }

/////////// 10

// class A {
//   int x;

//   void foo() {

//   }
// }