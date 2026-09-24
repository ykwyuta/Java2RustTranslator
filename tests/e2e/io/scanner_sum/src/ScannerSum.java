import java.util.Scanner;

public class ScannerSum {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        long sum = 0;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int v = sc.nextInt();
            sum += v;
            max = Math.max(max, v);
        }
        sc.nextLine();
        String name = sc.nextLine();
        System.out.println("sum=" + sum + " max=" + max);
        System.out.println("Hello, " + name.trim() + "!");
        while (sc.hasNext()) {
            System.out.println("token: " + sc.next());
        }
    }
}
