public class Loops {
    static int sum(int[] values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
    }

    public static void main(String[] args) {
        outer:
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (j == 2) continue outer;
                System.out.println(i + "," + j);
            }
        }
        int k = 0;
        do {
            k++;
        } while (k < 5);
        System.out.println(sum(new int[] {1, 2, 3}) + k);
    }
}
