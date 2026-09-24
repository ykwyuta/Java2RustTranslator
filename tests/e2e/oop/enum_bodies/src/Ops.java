import java.util.ArrayList;
import java.util.List;

public class Ops {
    enum Op {
        ADD("+") {
            @Override
            int apply(int a, int b) {
                return a + b;
            }
        },
        SUB("-") {
            @Override
            int apply(int a, int b) {
                return a - b;
            }
        },
        MUL("*") {
            @Override
            int apply(int a, int b) {
                return a * b;
            }

            @Override
            String describe() {
                return "times";
            }
        },
        DIV("/") {
            @Override
            int apply(int a, int b) {
                if (b == 0) {
                    throw new ArithmeticException("division by zero in " + this);
                }
                return a / b;
            }
        };

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        abstract int apply(int a, int b);

        String describe() {
            return name().toLowerCase() + " (" + symbol + ")";
        }
    }

    enum Planet {
        MERCURY(3.303e+23, 2.4397e6),
        EARTH(5.976e+24, 6.37814e6) {
            @Override
            String greeting() {
                return "home";
            }
        };

        final double mass;
        final double radius;

        Planet(double mass, double radius) {
            this.mass = mass;
            this.radius = radius;
        }

        String greeting() {
            return "far away";
        }

        double gravity() {
            return 6.67300E-11 * mass / (radius * radius);
        }
    }

    public static void main(String[] args) {
        for (Op op : Op.values()) {
            System.out.println(op + " " + op.ordinal() + " " + op.describe() + " 12,4 -> " + op.apply(12, 4));
        }
        System.out.println(Op.valueOf("MUL").apply(6, 7));
        System.out.println(Op.MUL.compareTo(Op.ADD) + " " + (Op.MUL == Op.valueOf("MUL")));
        try {
            Op.DIV.apply(1, 0);
        } catch (ArithmeticException e) {
            System.out.println(e.getMessage());
        }
        List<String> names = new ArrayList<>();
        for (Planet p : Planet.values()) {
            names.add(p.name() + ":" + p.greeting() + ":" + String.format("%.2f", p.gravity()));
        }
        System.out.println(names);
        Op op = Op.SUB;
        switch (op) {
            case ADD -> System.out.println("plus");
            case SUB -> System.out.println("minus");
            default -> System.out.println("other");
        }
        System.out.println(Op.ADD.getDeclaringClass() == Op.class);
    }
}
