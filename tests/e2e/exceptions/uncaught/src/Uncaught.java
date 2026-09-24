public class Uncaught {
    static int checkedDivide(int a, int b) {
        if (b == 0) {
            throw new IllegalArgumentException("b must not be zero");
        }
        return a / b;
    }

    public static void main(String[] args) {
        System.out.println(checkedDivide(10, 2));
        int[] a = new int[3];
        System.out.println("before");
        System.out.println(a[5]);
        System.out.println("after");
    }
}
