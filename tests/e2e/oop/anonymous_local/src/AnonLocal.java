import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AnonLocal {
    interface Counter {
        int next();
    }

    abstract static class Greeter {
        private final String prefix;

        Greeter(String prefix) {
            this.prefix = prefix;
        }

        abstract String name();

        String greet() {
            return prefix + ", " + name() + "!";
        }
    }

    private int base = 100;

    Counter counterFrom(int start) {
        return new Counter() {
            private int n = start;

            @Override
            public int next() {
                n++;
                return n + base;
            }
        };
    }

    static List<String> sortByLength(List<String> words) {
        List<String> copy = new ArrayList<>(words);
        Collections.sort(copy, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                if (a.length() != b.length()) {
                    return Integer.compare(a.length(), b.length());
                }
                return a.compareTo(b);
            }
        });
        return copy;
    }

    public static void main(String[] args) {
        System.out.println(sortByLength(List.of("pear", "fig", "banana", "kiwi", "apple")));

        AnonLocal outer = new AnonLocal();
        Counter c = outer.counterFrom(5);
        System.out.println(c.next() + " " + c.next());
        outer.base = 1000;
        System.out.println(c.next());

        String who = "world";
        Greeter g = new Greeter("Hello") {
            @Override
            String name() {
                return who;
            }
        };
        System.out.println(g.greet());

        Object o = new Object() {
            @Override
            public String toString() {
                return "custom object";
            }
        };
        System.out.println(o);

        int factor = 3;
        class Scaler {
            private final int offset;

            Scaler(int offset) {
                this.offset = offset;
            }

            int apply(int x) {
                return x * factor + offset;
            }
        }
        Scaler s = new Scaler(2);
        int total = 0;
        for (int i = 1; i <= 4; i++) {
            total += s.apply(i);
        }
        System.out.println("total=" + total);

        Runnable r = new Runnable() {
            @Override
            public void run() {
                System.out.println("run with factor " + factor);
            }
        };
        r.run();
    }
}
