package geo;

public interface Scalable {
    void scale(double factor);

    default void doubleSize() {
        scale(2);
    }
}
