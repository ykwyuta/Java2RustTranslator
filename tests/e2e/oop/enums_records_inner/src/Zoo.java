import java.util.ArrayList;
import java.util.List;

public class Zoo {
    enum Size { SMALL, MEDIUM, LARGE }

    enum Planet {
        MERCURY(3.303e+23, 2.4397e6), EARTH(5.976e+24, 6.37814e6), JUPITER(1.9e+27, 7.1492e7);

        private final double mass;
        private final double radius;
        static final double G = 6.67300E-11;

        Planet(double mass, double radius) {
            this.mass = mass;
            this.radius = radius;
        }

        double surfaceGravity() {
            return G * mass / (radius * radius);
        }

        String label() {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }
    }

    record Point(int x, int y) {
        Point {
            if (x < 0) throw new IllegalArgumentException("negative x: " + x);
        }

        Point scale(int k) {
            return new Point(x * k, y * k);
        }

        static Point origin() {
            return new Point(0, 0);
        }
    }

    record Named(String name, double value) {}

    private final String zooName;
    private final List<Animal> animals = new ArrayList<>();
    private int visitors;

    Zoo(String zooName) {
        this.zooName = zooName;
    }

    class Animal {
        final String kind;
        final Size size;

        Animal(String kind, Size size) {
            this.kind = kind;
            this.size = size;
        }

        String tag() {
            visitors++;
            return zooName + "/" + kind + "(" + size + ")";
        }
    }

    static class Ticket {
        private static int next = 100;
        final int number;

        Ticket() {
            number = next++;
        }
    }

    void add(String kind, Size size) {
        animals.add(new Animal(kind, size));
    }

    static String describe(Size s) {
        switch (s) {
            case SMALL:
                return "tiny";
            case MEDIUM:
                return "medium";
            default:
                return "huge";
        }
    }

    public static void main(String[] args) {
        Zoo zoo = new Zoo("CityZoo");
        zoo.add("cat", Size.SMALL);
        zoo.add("horse", Size.LARGE);
        zoo.add("dog", Size.MEDIUM);
        for (Animal a : zoo.animals) {
            System.out.println(a.tag() + " -> " + describe(a.size) + " ordinal=" + a.size.ordinal());
        }
        System.out.println("visitors=" + zoo.visitors);
        for (Size s : Size.values()) {
            String w = switch (s) {
                case SMALL -> "S";
                case MEDIUM -> "M";
                case LARGE -> "L";
            };
            System.out.print(s + "=" + w + " ");
        }
        System.out.println();
        System.out.println(Size.valueOf("MEDIUM") == Size.MEDIUM);
        System.out.println(Size.SMALL.compareTo(Size.LARGE) + " " + Size.LARGE.name());
        for (Planet p : Planet.values()) {
            System.out.printf("%s %.2f%n", p.label(), p.surfaceGravity());
        }
        Point p = new Point(3, 4);
        Point q = p.scale(2);
        System.out.println(p + " " + q + " " + p.equals(new Point(3, 4)) + " " + (p.hashCode() == new Point(3, 4).hashCode()));
        System.out.println(Point.origin() + " x=" + q.x() + " y=" + q.y());
        System.out.println(new Named("pi", 3.14) + " " + new Named("pi", 3.14).equals(new Named("pi", 3.14)));
        try {
            new Point(-1, 0);
        } catch (IllegalArgumentException e) {
            System.out.println("caught: " + e.getMessage());
        }
        Ticket t1 = new Ticket();
        Ticket t2 = new Ticket();
        System.out.println("tickets " + t1.number + " " + t2.number);
    }
}
