package geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Main {
    static class Counter {
        private int value;
        private final int step;

        Counter(int step) {
            this.step = step;
        }

        Counter() {
            this(1);
        }

        void tick() {
            value += step;
        }

        int get() {
            return value;
        }
    }

    public static void main(String[] args) {
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Circle(1.5));
        shapes.add(new Rect(2, 3));
        shapes.add(new Square(2));
        shapes.add(new Circle(0.5));
        for (Shape s : shapes) {
            System.out.println(s.describe());
        }
        Collections.sort(shapes);
        System.out.println("sorted: " + shapes);
        Shape biggest = Collections.max(shapes);
        System.out.println("biggest: " + biggest + " count=" + Shape.count());
        for (Shape s : shapes) {
            if (s instanceof Scalable sc) {
                sc.doubleSize();
                System.out.println("scaled " + s + " -> " + String.format("%.1f", s.area()));
            }
        }
        Rect a = new Rect(1, 2);
        Rect b = new Rect(1, 2);
        Shape c = new Square(1);
        System.out.println(a.equals(b) + " " + (a == b) + " " + a.equals(c) + " " + (a.hashCode() == b.hashCode()));
        Object o = c;
        System.out.println((o instanceof Rect) + " " + (o instanceof Square) + " " + (o instanceof Circle) + " " + (o instanceof Comparable));
        Counter k = new Counter(3);
        Counter k2 = new Counter();
        for (int i = 0; i < 4; i++) {
            k.tick();
            k2.tick();
        }
        System.out.println("counters: " + k.get() + " " + k2.get());
        Shape nothing = null;
        System.out.println("null shape: " + nothing + " " + (nothing == null));
    }
}
