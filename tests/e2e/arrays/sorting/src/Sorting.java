import java.util.Arrays;

public class Sorting {
    static void bubbleSort(int[] a) {
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j + 1 < a.length - i; j++) {
                if (a[j] > a[j + 1]) {
                    int tmp = a[j];
                    a[j] = a[j + 1];
                    a[j + 1] = tmp;
                }
            }
        }
    }

    static void quickSort(int[] a, int lo, int hi) {
        if (lo >= hi) return;
        int p = a[(lo + hi) >>> 1];
        int i = lo, j = hi;
        while (i <= j) {
            while (a[i] < p) i++;
            while (a[j] > p) j--;
            if (i <= j) {
                int t = a[i];
                a[i++] = a[j];
                a[j--] = t;
            }
        }
        quickSort(a, lo, j);
        quickSort(a, i, hi);
    }

    static long[][] pascal(int n) {
        long[][] t = new long[n][n];
        for (int i = 0; i < n; i++) {
            t[i][0] = 1;
            for (int j = 1; j <= i; j++) {
                t[i][j] = t[i - 1][j - 1] + t[i - 1][j];
            }
        }
        return t;
    }

    public static void main(String[] args) {
        int[] a = {9, 4, 7, 1, 8, 2, 6, 3, 5, 0};
        int[] b = Arrays.copyOf(a, a.length);
        bubbleSort(a);
        System.out.println(Arrays.toString(a));
        quickSort(b, 0, b.length - 1);
        System.out.println(Arrays.toString(b) + " " + Arrays.equals(a, b));
        long seed = 12345;
        int[] r = new int[20];
        for (int i = 0; i < r.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            r[i] = (int) (seed >>> 33) % 1000;
        }
        int[] head = Arrays.copyOfRange(r, 0, 3);
        System.out.println(Arrays.toString(head));
        Arrays.sort(r);
        System.out.println(Arrays.toString(r));
        long[][] p = pascal(10);
        System.out.println(Arrays.toString(p[9]));
        double[] ds = {3.5, -1.0, 2.25, 0.0, -0.0};
        Arrays.sort(ds);
        System.out.println(Arrays.toString(ds));
        char[] cs = "hello".toCharArray();
        Arrays.sort(cs);
        System.out.println(new String(cs) + " " + Arrays.toString(cs));
        boolean[] flags = new boolean[3];
        flags[1] = true;
        System.out.println(Arrays.toString(flags));
        int[] filled = new int[5];
        Arrays.fill(filled, 7);
        filled[2] += 3;
        filled[filled.length - 1]--;
        System.out.println(Arrays.toString(filled));
        int[][] jag = {{1}, {2, 3}, {4, 5, 6}};
        int total = 0;
        for (int[] row : jag) {
            for (int v : row) total += v;
        }
        System.out.println("total=" + total + " rows=" + jag.length + " last=" + jag[2].length);
        String[] words = {"pear", "apple", "fig"};
        Arrays.sort(words);
        System.out.println(Arrays.toString(words));
        int[] src = {1, 2, 3, 4, 5};
        System.arraycopy(src, 0, src, 1, 4);
        System.out.println(Arrays.toString(src));
    }
}
