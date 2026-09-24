package geo;

public class Circle extends Shape {
    private final double r;

    public Circle(double r) {
        super("Circle");
        this.r = r;
    }

    @Override
    public double area() {
        return Math.PI * r * r;
    }

    @Override
    public double perimeter() {
        return 2 * Math.PI * r;
    }
}
