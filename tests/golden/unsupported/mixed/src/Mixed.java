import java.util.ArrayList;
import java.util.List;

public class Mixed {
    private int value;
    static String[] names = new String[3];
    static String title;

    Mixed(int v) { value = v; }
    int get() { return value; }

    static int tryIt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static void main(String[] args) {
        List<Integer> list = new ArrayList<>();
        list.add(1);
        Integer boxed = 5;
        Mixed m = new Mixed(3);
        System.out.println(m.get() + list.size() + boxed);
        System.out.println(String.format("%d", 3));
        String s = null;
        Runnable r = () -> System.out.println("x");
        System.out.println(tryIt("12") + names.length);
    }
}
