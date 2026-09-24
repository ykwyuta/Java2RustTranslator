package geo;

public class Square extends Rect {
    public Square(double side) {
        super("Square", side, side);
    }

    @Override
    public String describe() {
        return "[square] " + super.describe();
    }
}
