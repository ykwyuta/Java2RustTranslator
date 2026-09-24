package geo;

public class Rect extends Shape implements Scalable {
    protected double w, h;

    public Rect(double w, double h) {
        this("Rect", w, h);
    }

    protected Rect(String name, double w, double h) {
        super(name);
        this.w = w;
        this.h = h;
    }

    @Override
    public double area() {
        return w * h;
    }

    @Override
    public double perimeter() {
        return 2 * (w + h);
    }

    @Override
    public void scale(double f) {
        w *= f;
        h *= f;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Rect other)) return false;
        return w == other.w && h == other.h;
    }

    @Override
    public int hashCode() {
        return (int) (w * 31 + h);
    }
}
