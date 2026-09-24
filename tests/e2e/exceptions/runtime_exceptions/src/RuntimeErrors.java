import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class RuntimeErrors {
    static class Node {
        int value;
        Node next;

        Node(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("negative: " + value);
            }
            this.value = value;
        }

        @Override
        public String toString() {
            if (value == 13) {
                throw new IllegalStateException("unlucky node");
            }
            return "Node(" + value + ")";
        }
    }

    abstract static class Shape {
        abstract double area();
    }

    static class Square extends Shape {
        final double side;

        Square(double side) {
            this.side = side;
        }

        @Override
        double area() {
            if (side < 0) {
                throw new ArithmeticException("negative side");
            }
            return side * side;
        }
    }

    static class Circle extends Shape {
        @Override
        double area() {
            return 3.0;
        }
    }

    interface Checker {
        boolean check(int x);

        default boolean checkAll(int[] xs) {
            for (int x : xs) {
                if (!check(x)) {
                    return false;
                }
            }
            return true;
        }
    }

    static String attempt(String label, Runnable r) {
        try {
            r.run();
            return label + ": ok";
        } catch (RuntimeException e) {
            // NullPointerException の詳細メッセージ（JDK 14 以降）は再現しないので表示しない。
            boolean npe = e instanceof NullPointerException;
            return label + ": " + e.getClass().getSimpleName() + (npe || e.getMessage() == null ? "" : " (" + e.getMessage() + ")");
        }
    }

    static int divide(int a, int b) {
        return a / b;
    }

    static int half(int a) {
        return a / 2;
    }

    static int sumUntilBad(int[] xs) {
        int sum = 0;
        for (int i = 0; ; i++) {
            try {
                sum += 100 / xs[i];
            } catch (ArithmeticException e) {
                sum -= 1;
                continue;
            } catch (ArrayIndexOutOfBoundsException e) {
                break;
            } finally {
                sum += 1000;
            }
        }
        return sum;
    }

    @SuppressWarnings("finally")
    static int finallyWins() {
        try {
            throw new RuntimeException("lost");
        } finally {
            return 7;
        }
    }

    public static void main(String[] args) {
        Node n = null;
        System.out.println(attempt("null field", () -> System.out.println(n.value)));
        System.out.println(attempt("null call", () -> System.out.println(n.toString())));
        String s = null;
        System.out.println(attempt("null string", () -> System.out.println(s.length())));
        System.out.println(attempt("null switch", () -> {
            switch (s) {
                case "a" -> System.out.println("a");
                default -> System.out.println("other");
            }
        }));
        Integer boxed = null;
        System.out.println(attempt("unboxing", () -> System.out.println(boxed + 1)));
        System.out.println(attempt("divide", () -> System.out.println(divide(1, 0))));
        System.out.println(half(-7) + " " + divide(Integer.MIN_VALUE, -1));
        Object o = "text";
        System.out.println(attempt("cast", () -> System.out.println((Integer) o)));
        int[][] grid = new int[2][3];
        System.out.println(attempt("array index", () -> System.out.println(grid[1][3])));
        System.out.println(attempt("array null row", () -> {
            int[][] jagged = new int[2][];
            jagged[0][0] = 1;
        }));
        System.out.println(attempt("negative size", () -> System.out.println(new int[-1].length)));
        System.out.println(attempt("parse", () -> System.out.println(Integer.parseInt("12a"))));
        System.out.println(attempt("constructor", () -> new Node(-5)));

        Node unlucky = new Node(13);
        System.out.println(attempt("toString in concat", () -> System.out.println("value: " + unlucky)));
        List<Node> nodes = new ArrayList<>();
        nodes.add(new Node(1));
        nodes.add(unlucky);
        System.out.println(attempt("toString in list", () -> System.out.println(nodes)));
        System.out.println(attempt("comparator", () -> nodes.sort(Comparator.comparing(x -> {
            if (x.value == 13) {
                throw new UnsupportedOperationException("cannot compare 13");
            }
            return x.value;
        }))));
        System.out.println(attempt("forEach", () -> nodes.forEach(x -> System.out.println(x.value / (x.value - 1)))));
        Map<String, Integer> counts = new HashMap<>();
        System.out.println(attempt("merge", () -> counts.merge("k", 1, (a, b) -> a / 0)));
        System.out.println(attempt("merge again", () -> counts.merge("k", 1, (a, b) -> a / 0)));
        System.out.println(counts);

        List<Shape> shapes = List.of(new Circle(), new Square(2), new Square(-1));
        double total = 0;
        for (Shape sh : shapes) {
            try {
                total += sh.area();
            } catch (ArithmeticException e) {
                System.out.println("skip: " + e.getMessage());
            }
        }
        System.out.println("total " + total);

        Checker positive = x -> {
            if (x == 0) {
                throw new IllegalArgumentException("zero");
            }
            return x > 0;
        };
        System.out.println(positive.checkAll(new int[]{1, 2, -3}));
        System.out.println(attempt("default method", () -> positive.checkAll(new int[]{1, 0})));

        Function<Integer, Integer> inverse = x -> 100 / x;
        System.out.println(attempt("function", () -> inverse.andThen(y -> y + 1).apply(0)));
        System.out.println(inverse.apply(4));

        System.out.println(sumUntilBad(new int[]{10, 0, 50, 0}));
        System.out.println(finallyWins());

        try {
            try {
                throw new IllegalStateException("inner");
            } finally {
                System.out.println("inner finally");
            }
        } catch (IllegalStateException e) {
            System.out.println("outer caught " + e.getMessage());
        }

        int[] data = {1, 2, 3};
        int idx = 5;
        data[idx % 3] = 9;
        System.out.println(data[2]);
        System.out.println(unlucky.value / (unlucky.value - 13));
    }
}
