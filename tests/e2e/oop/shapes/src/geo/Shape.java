package geo;

public abstract class Shape implements Comparable<Shape> {
    private static int created = 0;
    protected final String name;
    private int id;

    protected Shape(String name) {
        this.name = name;
        this.id = ++created;
    }

    public abstract double area();

    public double perimeter() {
        return 0;
    }

    public String describe() {
        return name + "#" + id + " area=" + String.format("%.2f", area()) + " perimeter=" + String.format("%.2f", perimeter());
    }

    public static int count() {
        return created;
    }

    @Override
    public int compareTo(Shape other) {
        return Double.compare(area(), other.area());
    }

    @Override
    public String toString() {
        return name + "(" + id + ")";
    }
}
