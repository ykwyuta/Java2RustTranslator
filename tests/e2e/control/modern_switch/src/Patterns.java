import java.util.List;

public class Patterns {
    sealed interface Expr permits Num, Add, Mul, Neg {}

    record Num(int value) implements Expr {}

    record Add(Expr left, Expr right) implements Expr {}

    record Mul(Expr left, Expr right) implements Expr {}

    record Neg(Expr inner) implements Expr {}

    static int eval(Expr e) {
        return switch (e) {
            case Num n -> n.value();
            case Add a -> eval(a.left()) + eval(a.right());
            case Mul m -> eval(m.left()) * eval(m.right());
            case Neg n -> -eval(n.inner());
        };
    }

    static String show(Expr e) {
        if (e instanceof Num n) return Integer.toString(n.value());
        if (e instanceof Add a) return "(" + show(a.left()) + " + " + show(a.right()) + ")";
        if (e instanceof Mul m && m.left() instanceof Num ln && ln.value() == 1) return show(m.right());
        if (e instanceof Mul m) return show(m.left()) + "*" + show(m.right());
        if (e instanceof Neg n) return "-" + show(n.inner());
        return "?";
    }

    static String describe(Object o) {
        return switch (o) {
            case null -> "null";
            case Integer i when i > 100 -> "big int " + i;
            case Integer i -> "int " + i;
            case String s when s.isEmpty() -> "empty string";
            case String s -> "string " + s.toUpperCase();
            case int[] arr -> "int array";
            default -> "other " + o;
        };
    }

    static int daysIn(int month, boolean leap) {
        int days;
        switch (month) {
            case 2:
                days = leap ? 29 : 28;
                break;
            case 4: case 6: case 9: case 11:
                days = 30;
                break;
            default:
                days = 31;
        }
        return days;
    }

    static String fallThrough(int n) {
        StringBuilder sb = new StringBuilder();
        switch (n) {
            case 1:
                sb.append("one ");
            case 2:
                sb.append("two ");
            case 3:
                sb.append("three ");
                break;
            case 4:
                sb.append("four ");
            default:
                sb.append("many ");
        }
        return sb.toString().trim();
    }

    static int score(String grade) {
        return switch (grade) {
            case "A", "A+" -> 4;
            case "B" -> 3;
            case "C" -> {
                int base = 1;
                yield base + 1;
            }
            default -> {
                if (grade.startsWith("D")) yield 1;
                yield 0;
            }
        };
    }

    public static void main(String[] args) {
        Expr e = new Add(new Num(2), new Mul(new Num(3), new Neg(new Num(4))));
        System.out.println(show(e) + " = " + eval(e));
        System.out.println(show(new Mul(new Num(1), new Num(7))));
        for (Object o : new Object[]{null, 5, 500, "", "hi", new int[]{1}, 2.5}) {
            System.out.println(describe(o));
        }
        for (int m : new int[]{1, 2, 4, 12}) {
            System.out.print(daysIn(m, m == 2) + " ");
        }
        System.out.println();
        for (int i = 1; i <= 5; i++) {
            System.out.println(i + ": " + fallThrough(i));
        }
        for (String g : List.of("A", "A+", "B", "C", "D-", "F")) {
            System.out.print(g + "=" + score(g) + " ");
        }
        System.out.println();
        String s = null;
        System.out.println("s=" + s + " " + (s == null) + " " + java.util.Objects.equals(s, null));
        Object nothing = null;
        try {
            nothing.toString();
        } catch (NullPointerException ex) {
            System.out.println("NPE caught");
        }
        int x = 7;
        String kind = switch (x % 3) {
            case 0 -> "fizz";
            case 1 -> "one";
            default -> {
                String t = "two";
                yield t.repeat(2);
            }
        };
        System.out.println(kind);
    }
}
