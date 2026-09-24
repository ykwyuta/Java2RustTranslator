import java.util.*;
import java.util.function.*;

public class Functional {
    @FunctionalInterface
    interface IntOp {
        int apply(int a, int b);
    }

    interface Greeter {
        String greet(String name);

        default Greeter twice() {
            return n -> greet(greet(n));
        }
    }

    static int fold(int[] xs, int init, IntOp op) {
        int acc = init;
        for (int x : xs) acc = op.apply(acc, x);
        return acc;
    }

    static <T, R> List<R> mapAll(List<T> in, Function<T, R> f) {
        List<R> out = new ArrayList<>();
        for (T t : in) out.add(f.apply(t));
        return out;
    }

    static <T> List<T> filter(List<T> in, Predicate<T> p) {
        List<T> out = new ArrayList<>();
        for (T t : in) {
            if (p.test(t)) out.add(t);
        }
        return out;
    }

    private int base = 10;

    int addBase(int x) {
        return x + base;
    }

    Supplier<Integer> baseSupplier() {
        return () -> base * 2;
    }

    static int square(int x) {
        return x * x;
    }

    public static void main(String[] args) {
        int[] xs = {1, 2, 3, 4, 5};
        System.out.println(fold(xs, 0, (a, b) -> a + b) + " " + fold(xs, 1, (a, b) -> a * b) + " " + fold(xs, 0, Math::max));
        int offset = 100;
        IntOp withOffset = (a, b) -> a + b + offset;
        System.out.println(withOffset.apply(1, 2));

        List<String> names = List.of("alice", "bob", "carol", "dave");
        System.out.println(mapAll(names, String::toUpperCase) + " " + mapAll(names, String::length));
        System.out.println(filter(names, n -> n.length() > 3) + " " + filter(List.of(5, 12, 7, 20), n -> n > 6));

        Greeter hello = n -> "Hello, " + n;
        System.out.println(hello.greet("Rust") + " | " + hello.twice().greet("Java"));

        Functional f = new Functional();
        Function<Integer, Integer> addB = f::addBase;
        Function<Integer, Integer> sq = Functional::square;
        System.out.println(addB.apply(5) + " " + addB.andThen(sq).apply(2) + " " + sq.compose(addB).apply(1) + " " + f.baseSupplier().get());

        BiFunction<String, Integer, String> rep = String::repeat;
        UnaryOperator<String> shout = s -> s + "!";
        BinaryOperator<Integer> mul = (a, b) -> a * b;
        Supplier<List<String>> maker = ArrayList::new;
        List<String> made = maker.get();
        made.add(rep.apply("ab", 3));
        made.add(shout.apply("hey"));
        System.out.println(made + " " + mul.apply(6, 7));

        Consumer<String> printer = s -> System.out.print("[" + s + "]");
        names.forEach(printer);
        System.out.println();
        Runnable r = () -> System.out.println("run!");
        r.run();

        Map<String, Integer> ages = new TreeMap<>();
        ages.put("bob", 25);
        ages.put("alice", 31);
        ages.put("carol", 19);
        ages.forEach((k, v) -> System.out.print(k + "=" + v + ";"));
        System.out.println();
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(ages.entrySet());
        entries.sort(Map.Entry.comparingByValue());
        System.out.println(entries);

        List<String> sorted = new ArrayList<>(names);
        sorted.sort((a, b) -> b.compareTo(a));
        System.out.println(sorted);
        sorted.sort(Comparator.comparingInt(String::length).thenComparing(Function.identity()));
        System.out.println(sorted);
        Predicate<String> startsWithC = s -> s.startsWith("c");
        System.out.println(filter(names, startsWithC.negate()) + " " + filter(names, startsWithC.or(s -> s.endsWith("e"))));

        int[] counter = {0};
        Runnable inc = () -> counter[0]++;
        for (int i = 0; i < 3; i++) inc.run();
        System.out.println("counter=" + counter[0]);
    }
}
