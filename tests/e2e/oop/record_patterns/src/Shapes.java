import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

public class Shapes {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Info {
        String value() default "";
    }

    record Point(int x, int y) {}

    sealed interface Shape permits Circle, Rect, Group {}

    @Info("round")
    record Circle(Point center, double radius) implements Shape {}

    record Rect(Point topLeft, Point bottomRight) implements Shape {}

    record Group(String name, List<Shape> members) implements Shape {}

    record Box<T>(T content) {}

    sealed interface Expr permits Num, Add, Mul, Neg {}

    record Num(int value) implements Expr {}

    record Add(Expr left, Expr right) implements Expr {}

    record Mul(Expr left, Expr right) implements Expr {}

    record Neg(Expr operand) implements Expr {}

    static double area(Shape s) {
        return switch (s) {
            case Circle(Point c, double r) -> Math.PI * r * r;
            case Rect(Point(int x1, int y1), Point(int x2, int y2)) -> Math.abs((x2 - x1) * (y2 - y1));
            case Group(String name, List<Shape> members) -> {
                double sum = 0;
                for (Shape m : members) {
                    sum += area(m);
                }
                yield sum;
            }
        };
    }

    static int eval(Expr e) {
        return switch (e) {
            case Num n -> n.value();
            case Add(Num(int a), Num(int b)) -> a + b;
            case Add(Expr l, Expr r) -> eval(l) + eval(r);
            case Mul(Num(var a), var r) when a == 0 -> 0;
            case Mul(Expr l, Expr r) -> eval(l) * eval(r);
            case Neg(Neg(var inner)) -> eval(inner);
            case Neg(var inner) -> -eval(inner);
        };
    }

    static String show(Expr e) {
        if (e instanceof Num(int v)) {
            return Integer.toString(v);
        }
        if (e instanceof Add(Expr l, Expr r)) {
            return "(" + show(l) + " + " + show(r) + ")";
        }
        if (e instanceof Mul(var l, var r)) {
            return show(l) + " * " + show(r);
        }
        if (e instanceof Neg(Expr inner)) {
            return "-" + show(inner);
        }
        return "?";
    }

    static String describe(Object o) {
        if (o instanceof Box<?>(String s)) {
            return "box of string " + s.length();
        }
        if (o instanceof Box<?>(Integer i) && i > 10) {
            return "box of big int " + i;
        }
        if (o instanceof Box<?>(var content)) {
            return "box of " + content;
        }
        if (o instanceof Point(var x, var y) && x == y) {
            return "diagonal point " + x;
        }
        return switch (o) {
            case Point(int x, int y) when x > 0 -> "right point " + x + "," + y;
            case Point p -> "point " + p;
            case String str -> "string " + str;
            default -> "something";
        };
    }

    public static void main(String[] args) {
        List<Shape> shapes = List.of(
                new Circle(new Point(0, 0), 1.5),
                new Rect(new Point(1, 2), new Point(4, 6)),
                new Group("g", List.of(new Rect(new Point(0, 0), new Point(2, 2)), new Circle(new Point(1, 1), 1))));
        for (Shape s : shapes) {
            System.out.printf("%s -> %.3f%n", s.getClass().getSimpleName(), area(s));
        }
        Expr e = new Add(new Mul(new Num(0), new Num(9)), new Neg(new Neg(new Add(new Num(2), new Num(3)))));
        System.out.println(show(e) + " = " + eval(e));
        System.out.println(eval(new Mul(new Num(3), new Add(new Num(1), new Neg(new Num(5))))));
        Object[] things = {new Box<>("hello"), new Box<>(42), new Box<>(7), new Box<>(null), new Point(3, 3),
                new Point(5, 1), new Point(-1, 2), "text", 3.5};
        for (Object o : things) {
            System.out.println(describe(o));
        }
        System.out.println(new Circle(new Point(1, 2), 3));
    }
}
